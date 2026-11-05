import { http } from './client'

/**
 * C 端宠物档案（ADR-014 §2，后端 CustomerPetController 已实现）。
 * 契约要点：
 *  - id 为 Long → JSON 字符串；weightKg 为 BigDecimal → 数字。
 *  - sizeCategory 由服务端按体重重算，客户端不可写（故不出现在请求体）。
 *  - vaccineSummary 只读（medical 事件同步字段），请求体不接受。
 *  - PUT 为全量更新：省略可空字段等于写回 NULL，故编辑表单必须回填全部字段。
 *  - 非归属/不存在统一 20101（防枚举），不区分「无权限」与「不存在」。
 */

export type PetSpecies = 'DOG' | 'CAT' | 'OTHER'
export type PetGender = 'MALE' | 'FEMALE' | 'UNKNOWN'
export type PetStatus = 'NORMAL' | 'DECEASED' | 'ARCHIVED'
export type PetSize = 'SMALL' | 'MEDIUM' | 'LARGE'

export interface PetVO {
  id: string
  name: string
  species: PetSpecies
  breed: string | null
  gender: PetGender | null
  isNeutered: boolean | null
  birthday: string | null
  birthdayApprox: boolean | null
  weightKg: number | null
  sizeCategory: PetSize | null
  /** 对象存储 key（ADR-029），**不是**可直接 <img src> 的地址 */
  avatar: string | null
  /** 后端 presigned GET（600s）；未上传、key 形态非法或不属于本人时为 null */
  avatarUrl: string | null
  remark: string | null
  vaccineSummary: string | null
  status: PetStatus
  createdAt: string
}

/** 创建/更新请求体（更新多一个 status）。 */
export interface PetForm {
  name: string
  species: PetSpecies
  breed: string | null
  gender: PetGender | null
  isNeutered: boolean | null
  birthday: string | null
  birthdayApprox: boolean | null
  weightKg: number | null
  avatar: string | null
  remark: string | null
  status?: PetStatus
}

export interface PetHealthVO {
  petId: string
  allergies: string[]
  chronicDiseases: string[]
  specialNotes: string | null
  updatedByRole: string | null
  updatedAt: string | null
}

export const petApi = {
  list: () => http.get<PetVO[]>('/customer/pets', { auth: true }),
  detail: (id: string) => http.get<PetVO>(`/customer/pets/${id}`, { auth: true }),
  create: (form: PetForm) => http.post<PetVO>('/customer/pets', form, { auth: true }),
  update: (id: string, form: PetForm) => http.put<PetVO>(`/customer/pets/${id}`, form, { auth: true }),
  remove: (id: string) => http.delete<void>(`/customer/pets/${id}`, { auth: true }),
  health: (id: string) => http.get<PetHealthVO>(`/customer/pets/${id}/health`, { auth: true }),
  updateHealth: (
    id: string,
    body: {
      allergies: string[]
      chronicDiseases: string[]
      specialNotes: string | null
      /** 读取时的 updatedAt；服务端在行锁内比较，冲突返回 10005 */
      expectedUpdatedAt?: string | null
    },
  ) => http.put<PetHealthVO>(`/customer/pets/${id}/health`, body, { auth: true }),
}
