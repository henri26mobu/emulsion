import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import type { Params } from './filter'

// Pont vers PhotoSaverPlugin.java (APK uniquement ; sur le web on retombe sur téléchargement / navigator.share)
interface PhotoSaverApi {
  begin(o: { name: string }): Promise<{ id: string }>
  append(o: { id: string; data: string }): Promise<void>
  finish(o: { id: string }): Promise<{ uri: string }>
  abort(o: { id: string }): Promise<void>
  share(o: { uri: string; mime?: string }): Promise<void>
  getShared(): Promise<{ path?: string | null; type?: string | null }>
  pickVideo(): Promise<{ path?: string }>
  exportVideo(o: { path: string; name: string; params: Params }): Promise<{ uri: string }>
  addListener(ev: 'shared', cb: (e: { path: string; type: string }) => void): Promise<PluginListenerHandle>
  addListener(ev: 'videoProgress', cb: (e: { percent: number }) => void): Promise<PluginListenerHandle>
}

export type Shared = { kind: 'video'; path: string } | { kind: 'photo'; blob: Blob }

const PhotoSaver = registerPlugin<PhotoSaverApi>('PhotoSaver')

export const isNative = Capacitor.isNativePlatform()

const CHUNK = 3 * 512 * 1024 // multiple de 3 : pas de padding base64 au milieu du flux

const b64 = (b: Blob) =>
  new Promise<string>((res, rej) => {
    const r = new FileReader()
    r.onload = () => res((r.result as string).split(',')[1])
    r.onerror = () => rej(r.error)
    r.readAsDataURL(b)
  })

/** Écrit le JPEG dans Pictures/Emulsion : visible tout de suite dans la Galerie. Renvoie son URI. */
export async function saveToGallery(file: File): Promise<string> {
  const { id } = await PhotoSaver.begin({ name: file.name })
  try {
    for (let i = 0; i < file.size; i += CHUNK) await PhotoSaver.append({ id, data: await b64(file.slice(i, i + CHUNK)) })
    return (await PhotoSaver.finish({ id })).uri
  } catch (e) {
    await PhotoSaver.abort({ id }).catch(() => {})
    throw e
  }
}

export const shareUri = (uri: string, mime = 'image/jpeg') => PhotoSaver.share({ uri, mime })

/** Sélecteur de vidéo Android ; renvoie le chemin de la copie dans le cache, ou null si annulé. */
export async function pickVideo(): Promise<string | null> {
  return (await PhotoSaver.pickVideo()).path ?? null
}

/** URL lisible par <video> pour une vidéo du cache (le nom du fichier ne change pas : on évite le cache du navigateur). */
export const videoSrc = (path: string) => `${Capacitor.convertFileSrc(path)}?t=${Date.now()}`

/** Filtre la vidéo (décodage → GPU → H.264) et l'enregistre dans Movies/Emulsion. Renvoie l'URI MediaStore. */
export async function exportVideo(path: string, params: Params, onProgress: (percent: number) => void): Promise<string> {
  const sub = await PhotoSaver.addListener('videoProgress', e => onProgress(e.percent))
  try {
    return (await PhotoSaver.exportVideo({ path, name: `emulsion-${Date.now()}.mp4`, params })).uri
  } finally {
    await sub.remove()
  }
}

async function fetchShared(path: string): Promise<Blob> {
  const r = await fetch(`${Capacitor.convertFileSrc(path)}?t=${Date.now()}`)
  if (!r.ok) throw new Error('Photo partagée illisible')
  return r.blob()
}

/** Photo ou vidéo envoyée via « Partager → Émulsion » : au démarrage, puis tant que l'appli reste ouverte. */
export function onShared(cb: (s: Shared) => void): () => void {
  if (!isNative) return () => {}
  const handle = (path?: string | null, type?: string | null) => {
    if (!path) return
    if (type === 'video') cb({ kind: 'video', path })
    else fetchShared(path).then(blob => cb({ kind: 'photo', blob })).catch(() => {})
  }
  PhotoSaver.getShared().then(r => handle(r.path, r.type))
  const sub = PhotoSaver.addListener('shared', e => handle(e.path, e.type))
  return () => void sub.then(s => s.remove())
}
