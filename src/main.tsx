import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

createRoot(document.getElementById('root')!).render(<App />)

// dans l'APK les fichiers sont déjà embarqués : pas de service worker
const native = !!(window as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor?.isNativePlatform?.()
if ('serviceWorker' in navigator && import.meta.env.PROD && !native) {
  navigator.serviceWorker.register('./sw.js').catch(() => {})
}
