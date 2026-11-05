import { http } from './client'

/**
 * 门店公开接口（store 第二段，ADR-010 §1 定形状、ADR-022 已落地：返回纯数组不分页）。
 * DEMO_STORES 仅作**请求失败时的兜底**（页面会黄条明示"演示数据"），正常路径走真实接口。
 * 价格单位为分（后端 Long 序列化为字符串，这里统一 Number 化）。
 */
export interface StoreServiceVO {
  serviceId: string
  name: string
  category: string
  price: number
  durationMin: number
}

export interface PublicStoreVO {
  id: string
  name: string
  address: string
  phone: string
  status: string
  services: StoreServiceVO[]
}

export const storeApi = {
  listPublicStores: (keyword?: string, signal?: AbortSignal) => {
    const q = keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''
    return http.get<PublicStoreVO[]>(`/public/stores${q}`, { signal })
  },
}

export const DEMO_STORES: PublicStoreVO[] = [
  {
    id: '9111',
    name: 'DemoStoreA',
    address: 'DemoRoad 1',
    phone: '13900009111',
    status: 'OPEN',
    services: [
      { serviceId: '401', name: '宠物洗澡', category: 'GROOMING', price: 6000, durationMin: 60 },
      { serviceId: '402', name: '宠物美容', category: 'GROOMING', price: 12000, durationMin: 90 },
      { serviceId: '405', name: '宠物诊疗', category: 'MEDICAL', price: 8800, durationMin: 30 },
    ],
  },
  {
    id: '9112',
    name: 'DemoStoreB',
    address: 'DemoRoad 2',
    phone: '13900009112',
    status: 'OPEN',
    services: [],
  },
]
