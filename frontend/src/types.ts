export interface User {
  id: string;
  username: string;
  globalAdmin: boolean;
  status: string;
  version: number;
}
export interface Project {
  id: string;
  name: string;
  status: string;
  role: "OWNER" | "MAINTAINER" | "VIEWER" | null;
  version: number;
}
export interface Summary {
  id: string;
  name: string;
  status: string;
  version: number;
  priority?: string;
  executionMode?: "ORDERED" | "INDEPENDENT";
  maxConcurrency?: number;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
export interface Member {
  userId: string;
  username: string;
  status: string;
  role: string;
  version: number;
}
export interface Policy {
  id: string;
  scheme: string;
  host: string;
  port: number;
  addressScope: string;
  enabled: boolean;
  version: number;
}

export interface RunMetrics {
  total: number;
  passed: number;
  failed: number;
  errors: number;
  skipped: number;
  cancelled: number;
  unknown: number;
  progress: number | null;
  coverage: number | null;
  passRate: number | null;
  failureRate: number | null;
  errorRate: number | null;
}
export interface Run {
  id: string;
  sourceType: string;
  sourceId: string;
  parentRunId: string | null;
  sourceName: string;
  executionMode: "ORDERED" | "INDEPENDENT";
  maxConcurrency: number;
  environmentId: string;
  environmentName: string;
  requestedByName: string;
  state: string;
  outcome: string | null;
  terminalReason: string | null;
  createdAt: string;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  deadlineAt: string;
  metrics: RunMetrics;
}
export interface RunItem {
  id: string;
  ordinal: number;
  caseName: string;
  state: string;
  durationMs: number | null;
  errorCode: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}
export interface RunReport {
  run: Run;
  items: RunItem[];
}
export interface RunDetail {
  item: RunItem;
  bodyAvailable: boolean;
  bodyTruncated: boolean;
  result: {
    configurationVersions?: { caseVersion: number; environmentVersion: number };
    request?: Record<string, unknown>;
    response?: {
      statusCode: number;
      durationMs: number;
      bodyBytes: number;
      headers: unknown[];
      body: unknown;
      bodyAvailable: boolean;
      bodyUnavailableReason: string | null;
    };
    assertions?: { id: string; type: string; passed: boolean }[];
    extractors?: { name: string; secret: boolean; success: boolean }[];
    errorCode?: string | null;
  } | null;
}

export interface GovernanceProject { id: string; name: string; status: string; version: number; activeOwners: number }
export interface OwnershipDetail { project: GovernanceProject; owners: { userId: string; username: string; status: string; version: number }[] }
export interface DisableImpact { userId: string; version: number; projects: { id: string; name: string; version: number; remainingActiveOwners: number; requiresTransfer: boolean }[] }
