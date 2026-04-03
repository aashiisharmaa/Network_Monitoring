// import TopNavbar from "./TopNavbar";
// import { useAuth } from "../../context/AutContext";
// import { useNavigate } from "react-router-dom";

// export default function MainLayout({ children }) {
//   const { logout, user, loading } = useAuth();
//   const navigate = useNavigate();

//   const handleLogout = async () => {
//     await logout();
//     navigate("/login", { replace: true });
//   };

//   return (
//     <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50">
//       <TopNavbar user={user} onLogout={handleLogout} loading={loading} />
//       <main className="w-full">{children}</main>
//     </div>
//   );
// }


import { useState } from "react";
import Sidebar from "./Sidebar";
import { useAuth } from "../../context/AutContext";
import { useNavigate } from "react-router-dom";

export default function MainLayout({ children }) {
  const { logout, user, loading } = useAuth();
  const navigate = useNavigate();
  const [isCollapsed, setIsCollapsed] = useState(true);

  const handleLogout = async () => {
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50 to-indigo-50">
      <Sidebar 
        user={user} 
        onLogout={handleLogout} 
        loading={loading}
        isCollapsed={isCollapsed}
        setIsCollapsed={setIsCollapsed}
      />
      
      {/* Main content with dynamic margin based on sidebar state */}
      <main 
        className={`transition-all duration-300 min-h-screen ${
          isCollapsed ? 'lg:ml-20' : 'lg:ml-64'
        }`}
      >
        <div>
          {children}
        </div>
      </main>
    </div>
  );
}