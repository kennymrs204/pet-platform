/** 遛狗任务 → 健康抽屉主语：只认队列里的真实 pet.id，不用 appointmentId 反推。 */
export interface WalkHealthPetRef {
  id: string
  name: string
}

export interface WalkHealthTaskRef {
  pet: WalkHealthPetRef | null
}

/** 无 pet:health:view（前端镜像）或宠物已删除时不返回目标，入口随之隐藏。 */
export function healthTargetFor(
  task: WalkHealthTaskRef | null | undefined,
  canView: boolean,
): WalkHealthPetRef | null {
  if (!canView || !task?.pet?.id) return null
  return { id: task.pet.id, name: task.pet.name }
}
