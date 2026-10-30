import { useEffect, useRef, useState } from 'react'
import Editor from './Editor'
import { FIGHT_CLUB, loadBitmap, type Params } from './filter'
import type { Media } from './media'
import { isNative, onShared, pickVideo, type Shared } from './native'

export default function App() {
  const [params, setParams] = useState<Params>(FIGHT_CLUB)
  const [media, setMedia] = useState<Media | null>(null)
  const [error, setError] = useState('')
  const input = useRef<HTMLInputElement>(null)

  // remplace le média courant en libérant l'ancienne photo
  const show = (m: Media | null) =>
    setMedia(old => {
      if (old?.kind === 'photo') old.bitmap.close()
      return m
    })

  const openPhoto = async (f: Blob) => {
    try {
      setError('')
      show({ kind: 'photo', bitmap: await loadBitmap(f) })
    } catch {
      setError('Image illisible')
    }
  }

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]
    e.target.value = ''
    if (f) openPhoto(f)
  }

  const onPickVideo = async () => {
    try {
      setError('')
      const path = await pickVideo()
      if (path) show({ kind: 'video', path })
    } catch (e) {
      setError(`Vidéo illisible : ${(e as Error).message}`)
    }
  }

  // « Partager → Émulsion » depuis la Galerie
  useEffect(() => onShared((s: Shared) => (s.kind === 'video' ? show({ kind: 'video', path: s.path }) : openPhoto(s.blob))), [])

  return (
    <>
      {media ? (
        <Editor media={media} params={params} setParams={setParams} onBack={() => show(null)} />
      ) : (
        <div className="home">
          <span className="brand">ÉMULSION</span>
          <button className="pick" onClick={() => input.current?.click()}>Choisir une photo</button>
          {isNative && <button className="pick alt" onClick={onPickVideo}>Choisir une vidéo</button>}
          {error && <p className="msg">{error}</p>}
        </div>
      )}
      <input ref={input} type="file" accept="image/*" hidden onChange={onPick} />
    </>
  )
}
