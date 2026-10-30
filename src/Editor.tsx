import { useEffect, useRef, useState } from 'react'
import { exportJpeg, NEUTRAL, Renderer, SLIDERS, type Params, type SliderDef } from './filter'
import type { Media } from './media'
import { exportVideo, isNative, saveToGallery, shareUri, videoSrc } from './native'
import { BUILTIN, loadCustom, saveCustom, type Preset } from './presets'

interface Props {
  media: Media
  params: Params
  setParams: (p: Params) => void
  onBack: () => void
}

export default function Editor({ media, params, setParams, onBack }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const rendererRef = useRef<Renderer>()
  const videoRef = useRef<HTMLVideoElement>()
  const [showOriginal, setShowOriginal] = useState(false)
  const [custom, setCustom] = useState<Preset[]>(loadCustom)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')

  const shown = showOriginal ? NEUTRAL : params
  const shownRef = useRef(shown)
  shownRef.current = shown // la boucle vidéo lit toujours les réglages courants sans se relancer

  useEffect(() => {
    rendererRef.current = new Renderer(canvasRef.current!)
    return () => rendererRef.current?.dispose()
  }, [])

  // Photo : long côté plafonné à 1600 px (fluide quand on glisse un curseur) ; l'export reste en pleine résolution.
  useEffect(() => {
    if (media.kind !== 'photo') return
    const { bitmap } = media
    const k = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height))
    rendererRef.current?.render(bitmap, Math.round(bitmap.width * k), Math.round(bitmap.height * k), shown, { seed: 1, mipmap: true })
  }, [media, shown])

  // Vidéo : lecture en boucle (sans son) rendue image par image par le même shader.
  useEffect(() => {
    if (media.kind !== 'video') return
    const v = document.createElement('video')
    v.src = videoSrc(media.path)
    v.loop = true
    v.muted = true
    v.playsInline = true
    v.play().catch(() => setMsg('Lecture impossible : touche l\'image'))
    videoRef.current = v
    let raf = 0
    let frame = 0
    const loop = () => {
      if (v.readyState >= 2 && v.videoWidth) {
        const k = Math.min(1, 900 / Math.min(v.videoWidth, v.videoHeight)) // aperçu léger : l'export est en pleine résolution
        rendererRef.current?.render(v, Math.round(v.videoWidth * k), Math.round(v.videoHeight * k), shownRef.current, { seed: ++frame })
      }
      raf = requestAnimationFrame(loop)
    }
    loop()
    return () => {
      cancelAnimationFrame(raf)
      v.pause()
      v.removeAttribute('src')
      v.load()
      videoRef.current = undefined
    }
  }, [media])

  const togglePlay = () => {
    const v = videoRef.current
    if (v) void (v.paused ? v.play() : v.pause())
  }

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    setMsg('')
    try {
      await fn()
    } catch (e) {
      if ((e as Error).name !== 'AbortError') setMsg((e as Error).message) // AbortError = l'utilisateur ferme la feuille de partage
    } finally {
      setBusy(false)
    }
  }

  const makeFile = async (bitmap: ImageBitmap) => {
    const blob = await exportJpeg(bitmap, params)
    return new File([blob], `emulsion-${Date.now()}.jpg`, { type: 'image/jpeg' })
  }

  const exportVid = (path: string) => exportVideo(path, params, p => setMsg(`Export de la vidéo… ${p} %`))

  const save = () =>
    run(async () => {
      if (media.kind === 'video') {
        await exportVid(media.path)
        setMsg('Vidéo enregistrée dans la Galerie, album Emulsion')
        return
      }
      const { bitmap } = media
      const f = await makeFile(bitmap)
      if (isNative) {
        await saveToGallery(f)
        setMsg(`Enregistrée dans la Galerie, album Emulsion (${bitmap.width}×${bitmap.height})`)
        return
      }
      const a = document.createElement('a')
      a.href = URL.createObjectURL(f)
      a.download = f.name
      a.click()
      setTimeout(() => URL.revokeObjectURL(a.href), 10000)
      setMsg(`JPEG ${bitmap.width}×${bitmap.height} enregistré`)
    })

  const share = () =>
    run(async () => {
      if (media.kind === 'video') {
        await shareUri(await exportVid(media.path), 'video/mp4')
        setMsg('')
        return
      }
      const f = await makeFile(media.bitmap)
      if (isNative) {
        // le partage Android a besoin d'un fichier : on l'enregistre d'abord dans la Galerie
        await shareUri(await saveToGallery(f))
        return
      }
      if (navigator.canShare?.({ files: [f] })) await navigator.share({ files: [f] })
      else throw new Error('Partage non supporté : utilise « Enregistrer »')
    })

  const addPreset = () => {
    const n = name.trim()
    if (!n) return
    const list = [...custom.filter(p => p.name !== n), { name: n, params }]
    setCustom(list)
    saveCustom(list)
    setNaming(false)
    setName('')
  }

  const removePreset = (n: string) => {
    const list = custom.filter(p => p.name !== n)
    setCustom(list)
    saveCustom(list)
  }

  const video = media.kind === 'video'

  const slider = (s: SliderDef) => (
    <label key={s.key} className="slider">
      <span>{s.label}</span>
      <input
        type="range"
        className={s.hue ? 'hue' : undefined}
        min={s.min}
        max={s.max}
        step={0.01}
        value={params[s.key]}
        onChange={e => setParams({ ...params, [s.key]: +e.target.value })}
      />
      <output>{s.hue ? `${Math.round(params[s.key] * 360)}°` : Math.round(params[s.key] * 100)}</output>
    </label>
  )

  return (
    <div className="editor">
      <div className="topbar">
        <button className="chip" onClick={onBack}>← {video ? 'Autre vidéo' : 'Autre photo'}</button>
        <button
          className="chip"
          onPointerDown={() => setShowOriginal(true)}
          onPointerUp={() => setShowOriginal(false)}
          onPointerLeave={() => setShowOriginal(false)}
          onContextMenu={e => e.preventDefault()}
        >
          Maintenir : original
        </button>
      </div>

      <div className="preview" onClick={video ? togglePlay : undefined}><canvas ref={canvasRef} /></div>

      <div className="panel">
        <div className="presets">
          {BUILTIN.map(p => (
            <button key={p.name} className="chip" onClick={() => setParams(p.params)}>{p.name}</button>
          ))}
          {custom.map(p => (
            <span key={p.name} className="chip custom">
              <button onClick={() => setParams(p.params)}>{p.name}</button>
              <button onClick={() => removePreset(p.name)} aria-label={`Supprimer ${p.name}`}>×</button>
            </span>
          ))}
          {naming ? (
            <span className="chip custom">
              <input autoFocus value={name} maxLength={24} placeholder="Nom" onChange={e => setName(e.target.value)} onKeyDown={e => e.key === 'Enter' && addPreset()} />
              <button onClick={addPreset}>OK</button>
            </span>
          ) : (
            <button className="chip" onClick={() => setNaming(true)}>+ Enregistrer</button>
          )}
        </div>

        {SLIDERS.filter(s => s.group === 'base').map(slider)}

        <details className="adv">
          <summary>Couleurs avancées</summary>
          {SLIDERS.filter(s => s.group === 'adv').map(slider)}
        </details>

        <div className="actions">
          <button disabled={busy} onClick={save}>{video ? 'Enregistrer la vidéo' : 'Enregistrer JPEG'}</button>
          <button className="primary" disabled={busy} onClick={share}>{busy ? '…' : 'Partager'}</button>
        </div>
        {video && !msg && <p className="msg">Aperçu sans son · touche l'image pour mettre en pause · le son est conservé à l'export</p>}
        {msg && <p className="msg">{msg}</p>}
      </div>
    </div>
  )
}
