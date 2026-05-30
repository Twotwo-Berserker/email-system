import request from './request'

/**
 * 用户注册
 */
export function register(data) {
  return request.post('/user/register', data)
}

/**
 * 用户登录
 */
export function login(data) {
  return request.post('/user/login', data)
}
