import http from './request'

export const login = (credentials) => http.post('/api/auth/login', credentials, { anonymous: true })
export const getMe = () => http.get('/api/auth/me')
export const getMyMenus = () => http.get('/api/sys/menus/my')
export const logout = () => http.post('/api/auth/logout', null, { ignoreUnauthorized: true })
