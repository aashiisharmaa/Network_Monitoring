import Router from "express";
import { logout, userLogin, userRegister } from "../controller/user.controller.ts";

const authRouter = Router();
authRouter.post("/register", userRegister);
authRouter.post("/login", userLogin);
authRouter.post("/logout", logout);
export { authRouter };
