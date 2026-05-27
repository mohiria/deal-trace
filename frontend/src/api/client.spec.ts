import { describe, expect, it } from 'vitest'
import type { AxiosHeaders, AxiosResponse } from 'axios'
import { ApiError, unwrapEnvelope } from './client'
import type { ApiEnvelope } from './client'

function fakeResponse<T>(envelope: ApiEnvelope<T>): AxiosResponse<ApiEnvelope<T>> {
  return {
    data: envelope,
    status: 200,
    statusText: 'OK',
    headers: {},
    config: { headers: {} as AxiosHeaders },
  }
}

describe('unwrapEnvelope', () => {
  it('returns inner data when code is SUCCESS', () => {
    const response = fakeResponse({ code: 'SUCCESS', message: 'OK', data: { status: 'UP' } })

    const result = unwrapEnvelope(response)

    expect(result).toEqual({ status: 'UP' })
  })

  it('throws ApiError carrying code and message when envelope is not SUCCESS', () => {
    const response = fakeResponse({
      code: 'VALIDATION_ERROR',
      message: '参数校验失败: value 不能为空',
      data: null,
    })

    expect(() => unwrapEnvelope(response)).toThrowError(ApiError)
    try {
      unwrapEnvelope(response)
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      const apiErr = err as ApiError
      expect(apiErr.code).toBe('VALIDATION_ERROR')
      expect(apiErr.message).toBe('参数校验失败: value 不能为空')
    }
  })
})
