import { ElMessage } from "element-plus";

let csrf: { token: string; headerName: string } | undefined;
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code?: string,
    public fieldErrors: unknown[] = [],
    public requestId?: string,
  ) {
    super(message);
  }
}
export function resetCsrf() {
  csrf = undefined;
}

export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
  extraHeaders: Record<string, string> = {},
): Promise<T> {
  const headers: Record<string, string> = {
    Accept: "application/json",
    ...extraHeaders,
  };
  if (method !== "GET") {
    if (!csrf) {
      const response = await fetch("/api/v1/auth/csrf", {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (!response.ok) throw new Error("无法建立安全会话，请检查服务是否就绪");
      csrf = (await response.json()).data;
    }
    headers["Content-Type"] = "application/json";
    headers[csrf!.headerName] = csrf!.token;
  }
  const response = await fetch("/api/v1" + path, {
    method,
    headers,
    credentials: "same-origin",
    cache: "no-store",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== "/auth/login")
      window.dispatchEvent(new Event("testflow:unauthenticated"));
    if (payload.error?.code === "CSRF_INVALID") resetCsrf();
    throw new ApiError(
      response.status,
      payload.error?.message ?? "请求失败，请检查服务或稍后重试",
      typeof payload.error?.code === "string" ? payload.error.code : undefined,
      Array.isArray(payload.error?.fieldErrors) ? payload.error.fieldErrors : [],
      typeof payload.requestId === "string" ? payload.requestId : undefined,
    );
  }
  return payload.data as T;
}

export function notifyError(error: unknown) {
  ElMessage.error(error instanceof Error ? error.message : "操作未完成");
}
export function parseJson<T>(text: string, label: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new Error(label + " 不是有效的 JSON");
  }
}
