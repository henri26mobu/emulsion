// Ce que l'éditeur travaille : une photo décodée, ou une vidéo copiée dans le cache de l'appli (chemin natif).
export type Media = { kind: 'photo'; bitmap: ImageBitmap } | { kind: 'video'; path: string }
