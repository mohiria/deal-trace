import axios from 'axios'
import type { AxiosInstance, AxiosResponse } from 'axios'

/**
 * 后端统一响应信封（与 backend ApiResponse<T> 对应）。
 */
export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
}

/**
 * 业务错误。`code` 来自后端 ErrorCode，便于前端做精细分支。
 */
export class ApiError extends Error {
  public readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

/** localStorage 中存放访问令牌的 key——令牌的单一来源（store 与 client 共用）。 */
export const TOKEN_STORAGE_KEY = 'dealtrace.token'

const UNAUTHORIZED_CODE = 'UNAUTHORIZED'

/**
 * 会话失效处理器：当任一响应的业务码为 `UNAUTHORIZED` 时调用。
 * 由 app 启动时注入"清登录态 + 跳登录页"的回调（D3 解耦：client 不 import store/router）。
 */
type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  unauthorizedHandler = handler
}

function triggerUnauthorized(): void {
  if (unauthorizedHandler) {
    unauthorizedHandler()
  }
}

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return (
    typeof value === 'object' &&
    value !== null &&
    'code' in value &&
    'message' in value &&
    typeof (value as { code: unknown }).code === 'string'
  )
}

/**
 * 把信封 unwrap 成业务负载；`code !== 'SUCCESS'` 抛 {@link ApiError}。
 *
 * 单独导出便于单测（不需要起 HTTP）。保持纯函数：不产生副作用。
 */
export function unwrapEnvelope<T>(response: AxiosResponse<ApiEnvelope<T>>): T {
  const envelope = response.data
  if (envelope.code !== 'SUCCESS') {
    throw new ApiError(envelope.code, envelope.message)
  }
  return envelope.data
}

export const apiClient: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10_000,
})

// 请求拦截器：从单一来源（localStorage）注入访问令牌。
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// 响应拦截器：成功 unwrap；失败归一为 ApiError；UNAUTHORIZED 分流到会话失效处理器。
apiClient.interceptors.response.use(
  (response) => {
    const envelope = response.data as ApiEnvelope<unknown>
    if (envelope.code === UNAUTHORIZED_CODE) {
      triggerUnauthorized()
    }
    return unwrapEnvelope(response)
  },
  (error: unknown) => {
    const data =
      typeof error === 'object' && error !== null && 'response' in error
        ? (error as { response?: { data?: unknown } }).response?.data
        : undefined

    if (isEnvelope(data)) {
      if (data.code === UNAUTHORIZED_CODE) {
        triggerUnauthorized()
      }
      return Promise.reject(new ApiError(data.code, data.message))
    }

    const message = error instanceof Error ? error.message : '网络请求失败'
    return Promise.reject(new ApiError('NETWORK_ERROR', message))
  },
)
