import { http } from './client'

/**
 * 服务人员侧「我的任务」读侧（ADR-048）。
 *
 * <p>为什么单开一个端点而不是复用 `GET /store/grooming/queue`：那个看板按
 * `cat_service.code ∈ {PET_BATH, PET_GROOMING}` 过滤（ADR-038 的口径），
 * 遛狗与诊疗的单在里面看不见——而 `task:start`/`task:finish` 遛狗师、医生都持有。
 * 能干活的人却没有看自己活的入口，这一类就是本文件要补的。
 *
 * 动作（开始/完成）不在这里：它们改的是 `apt_appointment` 状态，见 `api/bathing.ts` 的
 * `bathingApi.startService/finishService`（同一族路径挂同一张表，避免两个前缀指向同一行）。
 */

export type TaskStatus = 'CONFIRMED' | 'WAIT_SERVICE' | 'SERVING' | 'COMPLETED'

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  CONFIRMED: '已确认·待开始',
  WAIT_SERVICE: '待服务',
  SERVING: '服务中',
  COMPLETED: '已完成',
}

export interface TaskRow {
  appointmentId: string
  appointmentNo: string | null
  serviceDate: string
  startTime: string | null
  endTime: string | null
  status: string
  source: string | null
  serviceName: string | null
  /** 服务码是类型的唯一可靠判据（ADR-038）：服务名会被门店改，快照时长也会与目录不一致 */
  serviceCode: string | null
  durationMin: number | null
  price: string | number | null
  storeServiceId: string | null
  petId: string | null
  petName: string | null
  employeeId: string | null
}

export interface MineView {
  date: string
  statusFilter: string[]
  /** 服务端按 token 里的员工身份收窄；无员工身份时为 null 且 list 为空（如实空态，不是越权） */
  employeeId: string | null
  total: string | number
  /** 当天全部 TODO（CONFIRMED/WAIT_SERVICE/SERVING）真实总数，不受 status 筛选与列表 LIMIT 影响 */
  todoTotal: string | number
  list: TaskRow[] | null
}

export const tasksApi = {
  /** `GET /store/tasks/mine?date=&status=`（闸 `task:accept` 或 `appointment:view:store`） */
  mine: (p: { date?: string; status?: string } = {}) => {
    const q = new URLSearchParams()
    if (p.date) q.set('date', p.date)
    if (p.status) q.set('status', p.status)
    const s = q.toString()
    return http.get<MineView>(`/store/tasks/mine${s ? `?${s}` : ''}`, { auth: true })
  },
}
