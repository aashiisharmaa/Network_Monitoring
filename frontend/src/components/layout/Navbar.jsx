// src/components/layout/Navbar.jsx
export default function Navbar() {
  return (
    <header className="w-full bg-white shadow p-4 flex justify-between items-center">
      <h1 className="text-xl font-semibold">Network Monitor</h1>
      <div className="flex items-center gap-4">
        <button className="text-sm text-gray-600 hover:text-blue-600">
          Profile
        </button>
        <button className="text-sm text-red-500 hover:text-red-600">
          Logout
        </button>
      </div>
    </header>
  );
}
