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

/**
 * 把信封 unwrap 成业务负载；`code !== 'SUCCESS'` 抛 {@link ApiError}。
 *
 * 单独导出便于单测（不需要起 HTTP）。
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

apiClient.interceptors.response.use(
  (response) => unwrapEnvelope(response),
  (error) => Promise.reject(error),
)
