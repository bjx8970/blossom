export const DEVICE_TOKEN_ROTATE_AHEAD_MS = 7 * 24 * 60 * 60 * 1_000
export const MAX_DEVICE_TOKEN_TIMER_DELAY_MS = 2_147_000_000

type TimeoutHandle = ReturnType<typeof setTimeout>
type SetTimer = (callback: () => void, delayMs: number) => TimeoutHandle
type ClearTimer = (timer: TimeoutHandle) => void

/**
 * Maintains exactly one one-shot timer for the active account credential.
 * Long delays are re-armed because Node turns delays above 2^31-1 into 1 ms.
 */
export class DeviceTokenRotationTimer {
  private timer?: TimeoutHandle
  private generation = 0

  constructor(
    private readonly now: () => number = Date.now,
    private readonly setTimer: SetTimer = setTimeout,
    private readonly clearTimer: ClearTimer = clearTimeout
  ) {}

  clear(): void {
    this.generation += 1
    if (this.timer !== undefined) this.clearTimer(this.timer)
    this.timer = undefined
  }

  schedule(expireTime: string | undefined, onDue: () => void, minimumDelayMs = 0): boolean {
    this.clear()
    if (!expireTime) return false
    const expiresAt = Date.parse(expireTime)
    if (!Number.isFinite(expiresAt)) return false

    const dueAt = Math.max(expiresAt - DEVICE_TOKEN_ROTATE_AHEAD_MS, this.now() + Math.max(0, minimumDelayMs))
    const generation = this.generation
    const arm = (): void => {
      const remainingMs = Math.max(0, dueAt - this.now())
      const timer = this.setTimer(
        () => {
          if (this.timer === timer) this.timer = undefined
          if (generation !== this.generation) return
          if (dueAt > this.now()) {
            arm()
            return
          }
          onDue()
        },
        Math.min(remainingMs, MAX_DEVICE_TOKEN_TIMER_DELAY_MS)
      )
      this.timer = timer
    }
    arm()
    return true
  }
}
