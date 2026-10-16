const KEY = 'ihms-health-updated'
export function notifyHealthChange() {
  try { localStorage.setItem(KEY, `${Date.now()}-${Math.random()}`) } catch (_) { /* polling remains available */ }
  window.dispatchEvent(new Event(KEY))
}
export default {
  mounted() {
    this._healthRefreshRunning = false
    this._healthRefresh = async () => {
      if (document.hidden || this._healthRefreshRunning || (this.healthRefreshAllowed && !this.healthRefreshAllowed())) return
      this._healthRefreshRunning = true
      try { await this.refreshHealthData(true) } catch (_) { /* the page keeps the last successful result */ }
      finally { this._healthRefreshRunning = false }
    }
    this._healthStorage = e => { if (e.key === KEY) this._healthRefresh() }
    this._healthTimer = setInterval(this._healthRefresh, 15000)
    window.addEventListener(KEY, this._healthRefresh)
    window.addEventListener('storage', this._healthStorage)
    window.addEventListener('focus', this._healthRefresh)
    document.addEventListener('visibilitychange', this._healthRefresh)
  },
  beforeDestroy() {
    clearInterval(this._healthTimer)
    window.removeEventListener(KEY, this._healthRefresh)
    window.removeEventListener('storage', this._healthStorage)
    window.removeEventListener('focus', this._healthRefresh)
    document.removeEventListener('visibilitychange', this._healthRefresh)
  }
}
