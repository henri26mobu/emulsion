import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// base './' : l'appli marche à la racine comme dans un sous-dossier
export default defineConfig({ base: './', plugins: [react()] })
