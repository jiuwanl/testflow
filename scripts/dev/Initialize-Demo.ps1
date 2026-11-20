<#
.SYNOPSIS
向开发环境添加缺失的合成演示定义。
.DESCRIPTION
需要开发API和DemoTarget已就绪，使用local-admin及本机开发凭据。
.PARAMETER ApiPort
本工具连接或启动的API端口，按脚本默认值使用。
.PARAMETER TargetPort
本机合成HTTP目标端口，按脚本默认值使用。
.NOTES
会创建演示项目、目标授权、环境、用例和计划；不自动执行、不覆盖已有定义。
完整操作顺序见docs/DEPLOYMENT.md；测试条件见docs/TESTING.md。
#>
#requires -Version 7.0
param([int]$ApiPort=8080,[int]$TargetPort=8088)
. (Join-Path $PSScriptRoot '../lib/LocalEnvironment.ps1')
$config=Get-TestFlowConfig
$root="http://127.0.0.1:$ApiPort/api/v1"
$web=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Read-Json($Response) {
    $text=if ($Response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($Response.Content) } else { [string]$Response.Content }
    return $text | ConvertFrom-Json -Depth 40
}
$csrf=(Read-Json (Invoke-WebRequest "$root/auth/csrf" -WebSession $web)).data
$login=@{username='local-admin';password=$config.AdminPassword} | ConvertTo-Json -Compress
$null=Invoke-WebRequest "$root/auth/login" -Method Post -WebSession $web -ContentType application/json -Headers @{$csrf.headerName=$csrf.token} -Body $login
$csrf=(Read-Json (Invoke-WebRequest "$root/auth/csrf" -WebSession $web)).data
function Invoke-DemoApi([string]$Method,[string]$Path,$Body=$null) {
    $arguments=@{Uri="$root$Path";Method=$Method;WebSession=$web;TimeoutSec=15}
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Headers=@{$csrf.headerName=$csrf.token}; $arguments.Body=$Body | ConvertTo-Json -Depth 30 -Compress }
    return (Read-Json (Invoke-WebRequest @arguments)).data
}
$name='订单服务 · 合成演示'
$projects=(Invoke-DemoApi GET '/projects?size=100').items
$project=@($projects | Where-Object name -EQ $name | Select-Object -First 1)
if ($project.Count) { $projectId=$project[0].id } else {
    $owner=(Invoke-DemoApi GET '/auth/me').id
    $projectId=(Invoke-DemoApi POST '/projects' @{name=$name;ownerId=$owner}).id
}
$base="/projects/$projectId"
$envs=(Invoke-DemoApi GET "$base/environments?size=100").items
if (-not @($envs | Where-Object name -EQ '本机联调 · 合成目标').Count) {
    $null=Invoke-DemoApi POST "$base/environments" @{name='本机联调 · 合成目标';baseUrl="http://127.0.0.1:$TargetPort";variables=@{marker='TestFlow demo'}}
}
$policies=(Invoke-DemoApi GET "$base/target-policies?size=100").items
if (-not @($policies | Where-Object { $_.host -eq '127.0.0.1' -and $_.port -eq $TargetPort -and $_.scheme -eq 'http' }).Count) {
    $null=Invoke-DemoApi POST "$base/target-policies" @{scheme='http';host='127.0.0.1';port=$TargetPort;addressScope='LOOPBACK'}
}
$definitions=@(
    @{name='查询订单详情';path='/ok';timeout=15000;priority='P1'},
    @{name='异常状态诊断';path='/failed';timeout=15000;priority='P1'},
    @{name='等待响应与取消';path='/delay';timeout=15000;priority='P2'},
    @{name='响应大小边界';path='/large';timeout=15000;priority='P2'},
    @{name='流式响应总时限';path='/stream';timeout=800;priority='P2'}
)
$cases=(Invoke-DemoApi GET "$base/cases?size=100").items
foreach ($definition in $definitions) {
    if (@($cases | Where-Object name -EQ $definition.name).Count) { continue }
    $assertions=@(@{id='status';type='STATUS_CODE';expected=200})
    if ($definition.path -eq '/ok') { $assertions+=@{id='order-id';type='JSON_EQUALS';path='$.data.id';expected=42} }
    $null=Invoke-DemoApi POST "$base/cases" @{name=$definition.name;priority=$definition.priority;tags=@('合成演示','单用例');configSchemaVersion=1;config=@{
        request=@{method='GET';path=$definition.path;query=@();headers=@();body=@{type='NONE'}}
        assertions=$assertions;extractors=@();timeoutMs=$definition.timeout
    }}
}

# Stage 3: preserve existing definitions; add only missing synthetic business-flow cases/plans.
function Add-FlowCase([string]$Name,[string]$Path,[string]$Method,$Extractors=@(),$Headers=@(),$Body=@{type='NONE'},$Assertions=@(@{id='status';type='STATUS_CODE';expected=200})) {
    $existing=@((Invoke-DemoApi GET "$base/cases?size=100").items | Where-Object name -EQ $Name)
    if($existing.Count){return $existing[0].id}
    return (Invoke-DemoApi POST "$base/cases" @{name=$Name;priority='P1';tags=@('合成演示','有序回归');configSchemaVersion=1;config=@{
        request=@{method=$Method;path=$Path;query=@();headers=@($Headers);body=$Body};assertions=@($Assertions);extractors=@($Extractors);timeoutMs=15000
    }}).id
}
$login=Add-FlowCase '01 · 合成登录与令牌提取' '/flow/login' 'POST' @(@{name='token';path='$.data.token';secret=$true},@{name='quantity';path='$.data.quantity';secret=$false}) @() @{type='JSON';json=@{marker='${env.marker}'}}
$auth=@(@{name='Authorization';value='Bearer ${run.token}'})
$create=Add-FlowCase '02 · 创建订单与 ID 提取' '/flow/orders' 'POST' @(@{name='orderId';path='$.data.id';secret=$false}) $auth @{type='JSON';json=@{quantity='${run.quantity}';marker='${env.marker}'}}
$query=Add-FlowCase '03 · 查询订单与关联断言' '/flow/orders/${run.orderId}' 'GET' @() $auth @{type='NONE'} @(@{id='status';type='STATUS_CODE';expected=200},@{id='order-id';type='JSON_EQUALS';path='$.data.id';expected='${run.orderId}'})
$missing=Add-FlowCase '02 · 提取缺失诊断' '/flow/missing' 'GET' @(@{name='orderId';path='$.data.id';secret=$false})
$controlName='02 · 副作用等待（合成）'
$control=@((Invoke-DemoApi GET "$base/cases?size=100").items | Where-Object name -EQ $controlName)
if($control.Count){$controlId=$control[0].id}else{
 $controlId=(Invoke-DemoApi POST "$base/cases" @{name=$controlName;configSchemaVersion=1;config=@{
  request=@{method='POST';path='/mutate';query=@(@{name='ms';value='20000'});headers=@();body=@{type='NONE'}}
  timeoutMs=30000;assertions=@(@{id='status';type='STATUS_CODE';expected=200});extractors=@()
 }}).id
}
$plainId=@((Invoke-DemoApi GET "$base/cases?size=100").items | Where-Object name -EQ '查询订单详情')[0].id
$definitions=@(
 @{name='订单回归 · 登录 → 创建 → 查询';ids=@($login,$create,$query,$query);fast=$true},
 @{name='失败停止 · 提取异常后跳过';ids=@($login,$missing,$query);fast=$true},
 @{name='继续执行 · 展示依赖错误';ids=@($login,$missing,$query);fast=$false},
 @{name='运行控制 · 取消与中断';ids=@($login,$controlId,$plainId);fast=$true}
)
$plans=(Invoke-DemoApi GET "$base/plans?size=100").items
foreach($definition in $definitions){
 if(@($plans | Where-Object name -EQ $definition.name).Count){continue}
 $null=Invoke-DemoApi POST "$base/plans" @{name=$definition.name;executionMode='ORDERED';failFast=$definition.fast;steps=@($definition.ids | ForEach-Object { @{caseId=$_;enabled=$true} })}
}
# Stage 4: independent cases contain no run-variable references; no execution is submitted.
$parallelCases=@()
foreach($spec in @(@{name='独立巡检 · 慢响应';item='slow';ms='1800'},@{name='独立巡检 · 快响应';item='fast';ms='200'},@{name='独立巡检 · 常规响应';item='normal';ms='500'},@{name='独立巡检 · 长等待';item='wait';ms='10000'})){
 $existing=@((Invoke-DemoApi GET "$base/cases?size=100").items | Where-Object name -EQ $spec.name)
 if($existing.Count){$parallelCases+=$existing[0].id;continue}
 $parallelCases+=(Invoke-DemoApi POST "$base/cases" @{name=$spec.name;priority='P2';tags=@('合成演示','独立并行');configSchemaVersion=1;config=@{
  request=@{method='GET';path='/parallel/work';query=@(@{name='group';value='browser-demo'},@{name='item';value=$spec.item},@{name='ms';value=$spec.ms});headers=@();body=@{type='NONE'}}
  timeoutMs=15000;assertions=@(@{id='status';type='STATUS_CODE';expected=200});extractors=@()
 }}).id
}
$plans=(Invoke-DemoApi GET "$base/plans?size=100").items
$failureId=@((Invoke-DemoApi GET "$base/cases?size=100").items | Where-Object name -EQ '异常状态诊断')[0].id
foreach($spec in @(@{name='独立巡检 · 并发 2';ids=@($parallelCases[0],$parallelCases[1],$parallelCases[2]);fast=$false},@{name='并行控制 · 取消全部在途';ids=@($parallelCases[3],$parallelCases[3],$parallelCases[2],$parallelCases[1]);fast=$true},@{name='并行停止 · 保留在途结果';ids=@($parallelCases[3],$failureId,$parallelCases[1],$parallelCases[2]);fast=$true})){
 if(@($plans | Where-Object name -EQ $spec.name).Count){continue}
 $null=Invoke-DemoApi POST "$base/plans" @{name=$spec.name;executionMode='INDEPENDENT';maxConcurrency=2;failFast=$spec.fast;steps=@($spec.ids | ForEach-Object { @{caseId=$_;enabled=$true} })}
}
Write-Host "Synthetic demo definitions ready in project $projectId. Existing definitions were preserved; no runs were submitted."
