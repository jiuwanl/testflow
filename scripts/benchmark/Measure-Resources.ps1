<#
.SYNOPSIS
定时采集完整部署的CPU、内存等资源信息。
.DESCRIPTION
需要部署容器已运行；只观察，不主动产生压测负载。
.PARAMETER Minutes
采样总分钟数，默认30。
.PARAMETER IntervalSeconds
资源采样间隔秒数，默认30。
.NOTES
结果：.local/benchmarks/resources.jsonl；采样结束不会自动停止部署。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$Minutes=30,[int]$IntervalSeconds=30)
. (Join-Path $PSScriptRoot '../lib/ProjectPaths.ps1')
$ErrorActionPreference='Stop'
$root=$script:TestFlowRoot
$path=Join-Path $root '.local/benchmarks/resources.jsonl'
$null=New-Item -ItemType Directory -Path (Split-Path $path) -Force
$deadline=[DateTime]::UtcNow.AddMinutes($Minutes)
Push-Location $root
try {
    while([DateTime]::UtcNow -lt $deadline) {
        $ids=@(& docker compose -f compose.deploy.yml ps -q)
        if($LASTEXITCODE -ne 0 -or $ids.Count -eq 0){throw 'No deployment containers to sample.'}
        $stats=@(& docker stats --no-stream --format '{{json .}}' @ids)
        if($LASTEXITCODE -ne 0){throw 'Container resource sampling failed.'}
        $sample=@{at=[DateTime]::UtcNow.ToString('o');containers=@($stats|ForEach-Object{$_|ConvertFrom-Json})}
        if($IsWindows) {
            $os=Get-CimInstance Win32_OperatingSystem
            $sample.windows=@{totalKiB=$os.TotalVisibleMemorySize;freeKiB=$os.FreePhysicalMemory}
            $sample.desktop=@(Get-Process -Name idea64,msedge -ErrorAction SilentlyContinue | Group-Object ProcessName | ForEach-Object{@{name=$_.Name;processes=$_.Count;workingSetBytes=($_.Group|Measure-Object WorkingSet64 -Sum).Sum}})
        }
        [IO.File]::AppendAllText($path,($sample|ConvertTo-Json -Depth 8 -Compress)+"`n")
        Start-Sleep -Seconds $IntervalSeconds
    }
} finally {Pop-Location}
