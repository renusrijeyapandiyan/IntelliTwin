/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // soft warm light theme (no blue): cream page, sand sidebar, sage accent
        ink: '#3B3430', paper: '#FBF6EE', panel: '#FFFDF9', side: '#F3E9DB', line: '#E8DCCB',
        steel: '#5C7F63', steelDark: '#46664D', mute: '#7A6B5F',
        ok: '#3F8A5E', inspect: '#B07A1C', schedule: '#C46A2B', immediate: '#C94B45', stale: '#8A6A8C',
      },
      fontFamily: {
        display: ['"Barlow Semi Condensed"', 'system-ui', 'sans-serif'],
        sans: ['"IBM Plex Sans"', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
