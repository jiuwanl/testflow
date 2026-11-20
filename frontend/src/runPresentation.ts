export const stateNames: Record<string, string> = {
  QUEUED: "等待领取",
  RUNNING: "执行中",
  CANCELLING: "正在取消",
  COMPLETED: "执行结束",
  CANCELLED: "已取消",
  TIMED_OUT: "运行超时",
  INTERRUPTED: "已中断",
  PENDING: "尚未执行",
  PASSED: "通过",
  FAILED: "断言失败",
  ERROR: "执行错误",
  SKIPPED: "已跳过",
  UNKNOWN: "结果不确定",
};
export const reasons: Record<string, string> = {
  FAIL_FAST: "前序步骤失败，按计划策略跳过剩余步骤。已完成的结果保留。",
  VARIABLE_NOT_FOUND:
    "依赖的环境或前序运行变量不可用，本步骤未发送 HTTP 请求。",
  RUN_VARIABLE_LIMIT: "运行变量累计数量或大小超过限制，本次提取未发布。",
  LEASE_EXPIRED:
    "执行节点失联，平台已结束本次运行。已发出的请求可能产生副作用，请核查目标后再决定是否重新执行。",
  USER_CANCELLED:
    "用户取消了本次运行。在途请求可能已被目标处理，取消不会撤销外部操作。",
  RUN_DEADLINE: "已超过本次运行的整体期限，平台已停止继续派发。",
  AUTHORIZATION_REVOKED: "执行权限或目标授权已撤销，本次运行已停止。",
  REQUEST_TIMEOUT: "请求超过总时限。目标可能已处理请求，平台没有自动重试。",
  RESPONSE_TOO_LARGE: "响应超过安全上限，未使用截断内容判定通过。",
  NETWORK_ERROR: "未能完成网络请求，请检查目标服务及运行环境。",
  TLS_ERROR: "目标的 TLS 证书或主机名校验未通过。",
  DNS_FAILED: "目标域名无法解析。",
  DNS_TIMEOUT: "目标域名解析超时。",
  TARGET_ADDRESS_BLOCKED: "目标解析地址或端口不符合已授权的访问范围。",
  TARGET_NOT_AUTHORIZED: "该目标尚未获得访问授权。",
  SNAPSHOT_INVALID:
    "本次执行配置无法安全读取，请联系管理员核查密钥与配置版本。",
  RESPONSE_JSON_INVALID: "响应声明为 JSON，但正文无法安全解析。",
  EXTRACTION_FAILED: "响应变量提取未成功，请检查响应结构和提取路径。",
  WORKER_SHUTDOWN: "执行节点已停止，本次运行未能完整结束。",
  WORKER_EXECUTION_STOPPED: "执行节点无法继续处理本次运行。",
};
export const isTerminal = (state: string) =>
  ["COMPLETED", "CANCELLED", "TIMED_OUT", "INTERRUPTED"].includes(state);
export function tone(
  state: string,
): "success" | "warning" | "danger" | "info" | "primary" {
  if (state === "PASSED") return "success";
  if (["FAILED", "ERROR"].includes(state)) return "danger";
  if (["UNKNOWN", "INTERRUPTED", "TIMED_OUT"].includes(state)) return "warning";
  if (["RUNNING", "CANCELLING"].includes(state)) return "primary";
  return "info";
}
export const percentage = (value: number | null) =>
  value === null ? "N/A" : `${value}%`;
export function time(value: string | null) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "—";
}
