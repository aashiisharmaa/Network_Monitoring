export const statusCode = {
  OK: 200,
  CREATED: 201,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  UNSUPPORTED_MEDIA_TYPE: 415,
  TOO_MANY_REQUESTS: 429,

  INTERNAL_SERVER_ERROR: 500,
  NOT_IMPLEMENTED: 501,
};
export const authMessages = {
  USER_EXISTS_WITH_EMAIL: "User already exists with this email",
  USER_EXISTS_WITH_PHONE: "User already exists with this phone number",
  SIGNUP_SUCCESSFULLY: "User signed up successfully",
  PASSWORD_DO_NOT_MATCH: "Wrong Password",
  LOGIN_SUCCESSFULLY: "User logged in successfully",
  LOGOUT_SUCCESSFULLY: "Logout Successfully",
  PASSWORD_VALIDATION_FAILED:
    "Password must be at least 8 characters long and include uppercase, lowercase, digit, and special character.",
  INVALID_CREDENTIALS: "Invalid Credentials",
  USER_NOT_FOUND: "user not found",
  USER_NOT_FOUND_WITH_THIS_EMAIL: "User not found with this Email",
  AUTHENTICATION_MISSING: "Authenctication is missing",
  INVALID_TOKEN: "Token is invalid or expired",
  SESSION_EXPIRED: "Session expired,please login again",
  ALL_FIELDS_REQUIRED: "All fields are required",
  SERVER_ERROR: "Internal server error occurred",
};
export const authConstants = {
  PASSWORD_REGEX:
    /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/,
};
