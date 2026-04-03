// pages/RcaPage.jsx
import React from 'react';
import { Lock } from 'lucide-react'; // Make sure lucide-react is installed

export default function RcaPage() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[70vh] p-6">
      <div className="max-w-md w-full bg-white border border-slate-200 rounded-2xl shadow-xl p-10 text-center transition-all hover:shadow-2xl">
        
        {/* Icon with soft pulse effect */}
        <div className="relative mb-8 flex items-center justify-center mx-auto w-24 h-24 bg-amber-50 rounded-full">
          <div className="absolute inset-0 rounded-full bg-amber-100 animate-pulse opacity-50"></div>
          <Lock className="relative w-12 h-12 text-amber-500" />
        </div>

        {/* Text Content */}
        <h2 className="text-3xl font-extrabold text-slate-900 mb-4 tracking-tight">
          Feature Locked
        </h2>
        
        <p className="text-slate-500 leading-relaxed mb-8">
          Our intelligent recommendation engine requires a premium subscription. 
          <span className="block font-semibold text-slate-800 mt-2">
            Not available in Demo License.
          </span>
        </p>

        {/* Action Button */}
        <button 
          className="w-full py-3 px-6 bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded-xl transition-all transform active:scale-95 shadow-md"
          onClick={() => console.log('Upgrade clicked')}
        >
          Upgrade License
        </button>
        
        <p className="mt-6 text-xs text-slate-400 uppercase tracking-[0.2em] font-medium">
          Contact Support for Access
        </p>
      </div>
    </div>
  );
}