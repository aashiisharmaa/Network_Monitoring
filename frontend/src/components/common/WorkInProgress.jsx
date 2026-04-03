// components/WorkInProgress.jsx
import React from 'react'

export default function WorkInProgress({ title, description }) {
  return (
    <div className="min-h-screen bg-white flex items-center justify-center p-4">
      <div className="max-w-lg w-full text-center">
        {/* Animated Dot */}
        <div className="mb-8 flex justify-center">
          <div className="relative">
            <div className="w-3 h-3 bg-blue-600 rounded-full"></div>
            <div className="absolute inset-0 w-3 h-3 bg-blue-600 rounded-full animate-ping"></div>
          </div>
        </div>

        {/* Title */}
        <h1 className="text-4xl font-semibold text-gray-900 mb-4">
          {title}
        </h1>

        {/* Description */}
        <p className="text-gray-500 text-lg mb-8">
          {description || 'Coming soon'}
        </p>

        {/* Status */}
        <p className="text-sm text-gray-400 uppercase tracking-wider">
          In Development
        </p>
      </div>
    </div>
  )
}