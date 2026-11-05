import { apiBase, authHeader } from './client'
import { BizCode, BizError } from './types'

/**
 * 本地模拟支付通道（ADR-016 §8）：`POST /api/v1/payment/mock/{paymentNo}/notify`
 * 等价于渠道回调，走的是与真实回调同一条 handleCallback 链
 * （插 pay_payment → OrderPaidEvent → 预约 CONFIRMED → MULTI_DAY 预生成住单）。
 * 该端点仅当后端 `pet.payment.channel=mock` 时存在（生产 profile 下 404），
 * 且**不套统一信封**（按渠道要求应答纯文本），故不走 http 客户端。
 */
export async function mockNotifyPaid(paymentNo: string): Promise<string> {
  let res: Response
  try {
    res = await fetch(`${apiBase}/payment/mock/${encodeURIComponent(paymentNo)}/notify`, {
      method: 'POST',
      headers: authHeader(),
    })
  } catch {
    throw new BizError(BizCode.NETWORK, '模拟支付通道请求失败，请检查后端是否以 mock 通道启动')
  }
  const body = await res.text()
  if (!res.ok) throw new BizError(BizCode.NETWORK, `模拟支付通道应答异常（HTTP ${res.status}）：${body.slice(0, 80)}`)
  return body
}
