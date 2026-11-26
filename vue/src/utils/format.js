const chinaTime = { timeZone: 'Asia/Shanghai', hour12: false }
const shortDate = new Intl.DateTimeFormat('zh-CN', { ...chinaTime, month: '2-digit', day: '2-digit' })
const dateTime = new Intl.DateTimeFormat('zh-CN', {
  ...chinaTime, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
})

function formatDate(value, formatter) {
  const date = new Date(value)
  return value && Number.isFinite(date.getTime()) ? formatter.format(date) : '—'
}

export const formatShortDate = (value) => formatDate(value, shortDate)
export const formatDateTime = (value) => formatDate(value, dateTime)

export function formatDuration(milliseconds) {
  return milliseconds < 1000 ? milliseconds + ' ms' : (milliseconds / 1000).toFixed(1) + ' s'
}
