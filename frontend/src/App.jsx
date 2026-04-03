import { BrowserRouter, HashRouter } from "react-router-dom";
import { ThemeProvider, CssBaseline } from "@mui/material";
import AppRoutes from "./routes/AppRoutes";
import theme from "./styles/theme";
import { AuthProvider } from "./context/AutContext";

export default function App() {
  const isElectron =
    typeof navigator !== "undefined" &&
    navigator.userAgent.toLowerCase().includes("electron");
  const Router = isElectron ? HashRouter : BrowserRouter;

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Router>
        <AuthProvider>
          <AppRoutes />
        </AuthProvider>
      </Router>
    </ThemeProvider>
  );
}
