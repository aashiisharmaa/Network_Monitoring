import { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AutContext";
import { 
  User, 
  Lock, 
  LogIn, 
  UserPlus,
  Loader2, 
  AlertCircle, 
  Eye, 
  EyeOff, 
  Sparkles,
  Shield
} from "lucide-react";
import loginImage from "../../assets/images/login.jpg";

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [isRegisterMode, setIsRegisterMode] = useState(false);
  const [localMessage, setLocalMessage] = useState("");
  const { login, register, loading, error, isAuthenticated } = useAuth();
  const navigate = useNavigate();

  // Redirect if already authenticated
  useEffect(() => {
    if (isAuthenticated) {
      navigate("/", { replace: true });
    }
  }, [isAuthenticated, navigate]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLocalMessage("");

    if (isRegisterMode && password !== confirmPassword) {
      setLocalMessage("Passwords do not match.");
      return;
    }

    const result = isRegisterMode
      ? await register(email, password)
      : await login(email, password);

    if (result?.success) {
      navigate("/", { replace: true });
    }
  };

  return (
    <div
      className="relative flex items-center justify-center min-h-screen overflow-hidden"
      style={{
        backgroundImage: `url(${loginImage})`,
        backgroundSize: "cover",
        backgroundPosition: "center",
      }}
    >
      {/* Animated Gradient Overlay */}
      <div className="absolute inset-0 bg-gradient-to-br from-blue-900/40 via-indigo-900/50 to-purple-900/40 animate-gradient"></div>
      
      {/* Animated Particles */}
      <div className="absolute inset-0 overflow-hidden">
        <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-blue-500/10 rounded-full filter blur-3xl animate-float"></div>
        <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-purple-500/10 rounded-full filter blur-3xl animate-float-delayed"></div>
      </div>

      {/* Login Container */}
      <div className="relative z-10 w-full max-w-md mx-4 perspective-1000">
        {/* Decorative Elements */}
        <div className="absolute -top-10 -left-10 w-20 h-20 bg-gradient-to-br from-blue-400 to-indigo-600 rounded-full filter blur-2xl opacity-50 animate-pulse"></div>
        <div className="absolute -bottom-10 -right-10 w-20 h-20 bg-gradient-to-br from-purple-400 to-pink-600 rounded-full filter blur-2xl opacity-50 animate-pulse-delayed"></div>

        <form
          onSubmit={handleSubmit}
          className="backdrop-blur-2xl bg-white/5 border border-white/10 shadow-2xl rounded-3xl p-8 sm:p-10 transform transition-all duration-500 hover:shadow-blue-500/20 hover:shadow-3xl"
        >
          {/* Header with Icon */}
          <div className="flex flex-col items-center mb-8">
            <div className="relative mb-4">
              <div className="absolute inset-0 bg-gradient-to-r from-blue-500 to-indigo-600 rounded-2xl blur-lg opacity-75 animate-pulse"></div>
              <div className="relative bg-gradient-to-br from-blue-500 via-indigo-600 to-purple-600 p-4 rounded-2xl shadow-lg">
                <Sparkles className="w-8 h-8 text-white" />
              </div>
            </div>
            
            <h2 className="text-4xl font-bold mb-2 text-center bg-gradient-to-r from-white via-blue-100 to-indigo-200 bg-clip-text text-transparent">
              {isRegisterMode ? "Create Account" : "Welcome Back"}
            </h2>
            <p className="text-sm text-gray-300/80 text-center">
              {isRegisterMode
                ? "Create your first account to access the dashboard"
                : "Sign in to continue to your dashboard"}
            </p>
          </div>

          {/* Error Message */}
          {(error || localMessage) && (
            <div className="mb-6 p-4 bg-red-500/10 border border-red-400/30 rounded-xl flex items-start gap-3 backdrop-blur-sm animate-shake">
              <AlertCircle className="w-5 h-5 text-red-300 flex-shrink-0 mt-0.5" />
              <p className="text-sm text-red-200">{error || localMessage}</p>
            </div>
          )}

          {/* Email Input */}
          <div className="mb-6 group">
            <label className="block text-sm font-semibold mb-2 text-gray-200/90 transition-colors group-focus-within:text-blue-300">
              Email Address
            </label>
            <div className="relative">
              <div className="absolute inset-0 bg-gradient-to-r from-blue-500/20 to-indigo-500/20 rounded-xl blur-sm opacity-0 group-focus-within:opacity-100 transition-opacity duration-300"></div>
              <div className="relative flex items-center bg-white/5 border border-white/10 rounded-xl px-4 py-3.5 focus-within:border-blue-400/50 focus-within:bg-white/10 transition-all duration-300">
                <User className="w-5 h-5 text-gray-400 mr-3 transition-colors group-focus-within:text-blue-400" />
                <input
                  type="email"
                  placeholder="Enter your email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  disabled={loading}
                  required
                  className="bg-transparent flex-1 outline-none text-white placeholder-gray-500 disabled:opacity-50 text-sm"
                />
              </div>
            </div>
          </div>

          {/* Password Input */}
          <div className="mb-6 group">
            <label className="block text-sm font-semibold mb-2 text-gray-200/90 transition-colors group-focus-within:text-blue-300">
              Password
            </label>
            <div className="relative">
              <div className="absolute inset-0 bg-gradient-to-r from-blue-500/20 to-indigo-500/20 rounded-xl blur-sm opacity-0 group-focus-within:opacity-100 transition-opacity duration-300"></div>
              <div className="relative flex items-center bg-white/5 border border-white/10 rounded-xl px-4 py-3.5 focus-within:border-blue-400/50 focus-within:bg-white/10 transition-all duration-300">
                <Lock className="w-5 h-5 text-gray-400 mr-3 transition-colors group-focus-within:text-blue-400" />
                <input
                  type={showPassword ? "text" : "password"}
                  placeholder="Enter your password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                  required
                  className="bg-transparent flex-1 outline-none text-white placeholder-gray-500 disabled:opacity-50 text-sm"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="ml-2 text-gray-400 hover:text-white transition-colors"
                  tabIndex={-1}
                >
                  {showPassword ? (
                    <EyeOff className="w-5 h-5" />
                  ) : (
                    <Eye className="w-5 h-5" />
                  )}
                </button>
              </div>
            </div>
          </div>

          {/* Confirm Password Input (Register Mode) */}
          {isRegisterMode && (
            <div className="mb-6 group">
              <label className="block text-sm font-semibold mb-2 text-gray-200/90 transition-colors group-focus-within:text-blue-300">
                Confirm Password
              </label>
              <div className="relative">
                <div className="absolute inset-0 bg-gradient-to-r from-blue-500/20 to-indigo-500/20 rounded-xl blur-sm opacity-0 group-focus-within:opacity-100 transition-opacity duration-300"></div>
                <div className="relative flex items-center bg-white/5 border border-white/10 rounded-xl px-4 py-3.5 focus-within:border-blue-400/50 focus-within:bg-white/10 transition-all duration-300">
                  <Lock className="w-5 h-5 text-gray-400 mr-3 transition-colors group-focus-within:text-blue-400" />
                  <input
                    type={showPassword ? "text" : "password"}
                    placeholder="Confirm your password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    disabled={loading}
                    required
                    className="bg-transparent flex-1 outline-none text-white placeholder-gray-500 disabled:opacity-50 text-sm"
                  />
                </div>
              </div>
            </div>
          )}

          {!isRegisterMode && (
            <div className="flex items-center justify-end mb-8">
              <button
                type="button"
                className="text-sm text-blue-300 hover:text-blue-200 transition-colors font-medium"
              >
                Forgot password?
              </button>
            </div>
          )}

          {/* Submit Button */}
          <button
            type="submit"
            disabled={loading}
            className={`relative w-full flex items-center justify-center gap-2 font-bold py-4 rounded-xl shadow-lg transition-all duration-300 overflow-hidden group ${
              loading
                ? "bg-gray-500/30 cursor-not-allowed"
                : "bg-gradient-to-r from-blue-500 via-indigo-600 to-purple-600 hover:shadow-2xl hover:shadow-blue-500/50 transform hover:scale-[1.02] active:scale-[0.98]"
            }`}
          >
            {/* Button Shine Effect */}
            {!loading && (
              <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/20 to-transparent -translate-x-full group-hover:translate-x-full transition-transform duration-1000"></div>
            )}
            
            <span className="relative flex items-center gap-2">
              {loading ? (
                <>
                  <Loader2 className="w-5 h-5 animate-spin" />
                  <span>{isRegisterMode ? "Creating account..." : "Signing in..."}</span>
                </>
              ) : (
                <>
                  {isRegisterMode ? (
                    <UserPlus className="w-5 h-5" />
                  ) : (
                    <LogIn className="w-5 h-5" />
                  )}
                  <span>{isRegisterMode ? "Create Account" : "Sign In"}</span>
                </>
              )}
            </span>
          </button>

          {/* Footer */}
          <div className="mt-8 text-center space-y-4">
            <p className="text-sm text-gray-400">
              {isRegisterMode ? "Already have an account? " : "Don't have an account? "}
              <button
                type="button"
                onClick={() => {
                  setIsRegisterMode((prev) => !prev);
                  setLocalMessage("");
                  setConfirmPassword("");
                }}
                className="text-blue-300 hover:text-blue-200 font-semibold transition-colors"
              >
                {isRegisterMode ? "Sign In" : "Create one now"}
              </button>
            </p>
            
            <div className="flex items-center justify-center gap-2 text-xs text-gray-500">
              <Shield className="w-4 h-4" />
              <span>Authorized access only</span>
            </div>
          </div>
        </form>
      </div>

      {/* Custom Animations */}
      <style jsx>{`
        @keyframes gradient {
          0%, 100% {
            opacity: 1;
          }
          50% {
            opacity: 0.8;
          }
        }

        @keyframes float {
          0%, 100% {
            transform: translateY(0) translateX(0);
          }
          50% {
            transform: translateY(-20px) translateX(10px);
          }
        }

        @keyframes float-delayed {
          0%, 100% {
            transform: translateY(0) translateX(0);
          }
          50% {
            transform: translateY(20px) translateX(-10px);
          }
        }

        @keyframes pulse-delayed {
          0%, 100% {
            opacity: 0.5;
          }
          50% {
            opacity: 0.3;
          }
        }

        @keyframes shake {
          0%, 100% {
            transform: translateX(0);
          }
          25% {
            transform: translateX(-5px);
          }
          75% {
            transform: translateX(5px);
          }
        }

        .animate-gradient {
          animation: gradient 8s ease-in-out infinite;
        }

        .animate-float {
          animation: float 6s ease-in-out infinite;
        }

        .animate-float-delayed {
          animation: float-delayed 8s ease-in-out infinite;
        }

        .animate-pulse-delayed {
          animation: pulse-delayed 3s ease-in-out infinite;
        }

        .animate-shake {
          animation: shake 0.5s ease-in-out;
        }

        .perspective-1000 {
          perspective: 1000px;
        }
      `}</style>
    </div>
  );
}
