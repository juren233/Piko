import type { Context } from "hono";

export type AppErrorCode =
  | "INVALID_EMAIL"
  | "INVALID_PASSWORD"
  | "INVALID_USERNAME"
  | "INVALID_NICKNAME"
  | "INVALID_BODY"
  | "EMAIL_TAKEN"
  | "USERNAME_TAKEN"
  | "INVALID_CREDENTIALS"
  | "SESSION_EXPIRED"
  | "USER_NOT_FOUND"
  | "SELF_FRIEND_REQUEST"
  | "FRIEND_REQUEST_EXISTS"
  | "ALREADY_FRIENDS"
  | "FRIEND_REQUEST_NOT_FOUND"
  | "FRIEND_REQUEST_FORBIDDEN"
  | "INVALID_SEARCH_QUERY"
  | "DEVICE_NOT_FOUND"
  | "DEVICE_NOT_OWNED"
  | "DEVICE_OFFLINE"
  | "INVALID_PUBLIC_KEY"
  | "TRANSFER_SESSION_NOT_FOUND"
  | "TRANSFER_SESSION_FORBIDDEN"
  | "TRANSFER_PEER_NOT_FRIEND"
  | "SIGNALING_RATE_LIMITED"
  | "INTERNAL";

const STATUS_BY_CODE: Record<AppErrorCode, number> = {
  INVALID_EMAIL: 400,
  INVALID_PASSWORD: 400,
  INVALID_USERNAME: 400,
  INVALID_NICKNAME: 400,
  INVALID_BODY: 400,
  EMAIL_TAKEN: 409,
  USERNAME_TAKEN: 409,
  INVALID_CREDENTIALS: 401,
  SESSION_EXPIRED: 401,
  USER_NOT_FOUND: 404,
  SELF_FRIEND_REQUEST: 400,
  FRIEND_REQUEST_EXISTS: 409,
  ALREADY_FRIENDS: 409,
  FRIEND_REQUEST_NOT_FOUND: 404,
  FRIEND_REQUEST_FORBIDDEN: 403,
  INVALID_SEARCH_QUERY: 400,
  DEVICE_NOT_FOUND: 404,
  DEVICE_NOT_OWNED: 403,
  DEVICE_OFFLINE: 409,
  INVALID_PUBLIC_KEY: 400,
  TRANSFER_SESSION_NOT_FOUND: 404,
  TRANSFER_SESSION_FORBIDDEN: 403,
  TRANSFER_PEER_NOT_FRIEND: 403,
  SIGNALING_RATE_LIMITED: 429,
  INTERNAL: 500,
};

const MESSAGE_BY_CODE: Record<AppErrorCode, string> = {
  INVALID_EMAIL: "邮箱格式有误",
  INVALID_PASSWORD: "密码至少 8 位",
  INVALID_USERNAME: "用户名格式不合法",
  INVALID_NICKNAME: "昵称格式不合法",
  INVALID_BODY: "请求体格式不合法",
  EMAIL_TAKEN: "邮箱已被注册",
  USERNAME_TAKEN: "用户名已被占用",
  INVALID_CREDENTIALS: "邮箱或密码错误",
  SESSION_EXPIRED: "登录状态已失效，请重新登录",
  USER_NOT_FOUND: "用户不存在",
  SELF_FRIEND_REQUEST: "不能添加自己为好友",
  FRIEND_REQUEST_EXISTS: "已存在待处理的好友申请",
  ALREADY_FRIENDS: "已是好友",
  FRIEND_REQUEST_NOT_FOUND: "好友申请不存在",
  FRIEND_REQUEST_FORBIDDEN: "无权操作该好友申请",
  INVALID_SEARCH_QUERY: "搜索关键字格式不合法",
  DEVICE_NOT_FOUND: "设备不存在",
  DEVICE_NOT_OWNED: "无权操作该设备",
  DEVICE_OFFLINE: "目标设备当前不在线",
  INVALID_PUBLIC_KEY: "公钥格式不合法",
  TRANSFER_SESSION_NOT_FOUND: "传输会话不存在",
  TRANSFER_SESSION_FORBIDDEN: "无权操作该传输会话",
  TRANSFER_PEER_NOT_FRIEND: "双方不是好友且不属于同账号",
  SIGNALING_RATE_LIMITED: "信令请求过于频繁",
  INTERNAL: "服务器内部错误，请稍后重试",
};

export class AppError extends Error {
  readonly code: AppErrorCode;
  readonly status: number;

  constructor(code: AppErrorCode, message?: string) {
    super(message ?? MESSAGE_BY_CODE[code]);
    this.code = code;
    this.status = STATUS_BY_CODE[code];
  }
}

export function appErrorResponse(c: Context, error: AppError): Response {
  return c.json(
    { error: { code: error.code, message: error.message } },
    error.status as 400 | 401 | 403 | 404 | 409 | 429 | 500,
  );
}
