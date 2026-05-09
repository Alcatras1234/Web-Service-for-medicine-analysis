"use client"

import { useEffect, useRef, useState } from "react"
import { useParams } from "next/navigation"
import Link from "next/link"

type SlideInfo = {
  slideId: number
  width: number
  height: number
  tileSize: number
  maxLevel: number
  mppX: number | null
  mppY: number | null
}

type Detections = {
  totalEosinophils: number
  maxHpfCount: number
  maxHpfX: number
  maxHpfY: number
  diagnosis: string | null
  modelVersion: string | null
}

export default function StandaloneViewerPage() {
  const params = useParams<{ slideId: string }>()
  const slideId = params.slideId
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<any>(null)
  const [info, setInfo] = useState<SlideInfo | null>(null)
  const [detections, setDetections] = useState<Detections | null>(null)
  const [showOverlay, setShowOverlay] = useState(false)
  const [overlayLoading, setOverlayLoading] = useState(false)

  useEffect(() => {
    let cancelled = false

    async function init() {
      const [iRes, dRes] = await Promise.all([
        fetch(`/api/iiif/${slideId}/info.json`),
        fetch(`/api/slides/${slideId}/detections`),
      ])
      if (!iRes.ok) return
      const i: SlideInfo = await iRes.json()
      const d: Detections | null = dRes.ok ? await dRes.json() : null
      if (cancelled) return
      setInfo(i)
      setDetections(d)

      const OpenSeadragon = (await import("openseadragon")).default
      if (!containerRef.current || cancelled) return

      viewerRef.current?.destroy?.()
      viewerRef.current = OpenSeadragon({
        element: containerRef.current,
        prefixUrl: "https://cdnjs.cloudflare.com/ajax/libs/openseadragon/4.1.0/images/",
        showNavigator: true,
        tileSources: {
          height: i.height,
          width: i.width,
          tileSize: i.tileSize,
          minLevel: 0,
          maxLevel: i.maxLevel,
          getTileUrl: (level: number, x: number, y: number) =>
            `/api/iiif/${slideId}/tile/${level}/${x}_${y}.jpg`,
        },
      })

      if (d && d.maxHpfCount > 0 && i.mppX) {
        const win = Math.round(Math.sqrt(0.3) * 1000 / i.mppX)
        const overlay = document.createElement("div")
        overlay.style.border = "3px solid red"
        overlay.style.boxShadow = "0 0 12px rgba(255,0,0,0.6)"
        overlay.title = `HPF max: ${d.maxHpfCount} eos`
        viewerRef.current.addOverlay({
          element: overlay,
          location: viewerRef.current.viewport.imageToViewportRectangle(
            d.maxHpfX, d.maxHpfY, win, win
          ),
        })
      }
    }

    init()
    return () => {
      cancelled = true
      viewerRef.current?.destroy?.()
    }
  }, [slideId])

  function zoomToHpf() {
    if (!viewerRef.current || !detections || !info?.mppX) return
    const win = Math.round(Math.sqrt(0.3) * 1000 / info.mppX)
    const rect = viewerRef.current.viewport.imageToViewportRectangle(
      detections.maxHpfX, detections.maxHpfY, win, win
    )
    viewerRef.current.viewport.fitBounds(rect)
  }

  async function toggleOverlay() {
    const v = viewerRef.current
    if (!v) return
    if (showOverlay) {
      const elems = document.querySelectorAll<HTMLElement>("[data-detection]")
      elems.forEach(el => v.removeOverlay(el))
      setShowOverlay(false)
      return
    }
    setOverlayLoading(true)
    try {
      const res = await fetch(`/api/slides/${slideId}/detections/full`)
      if (!res.ok) return
      const data = await res.json()
      const dets: { cls: string; cx: number; cy: number }[] = data.detections || []
      const MAX_OVERLAY = 5000
      const sample = dets.length > MAX_OVERLAY
        ? dets.filter((_, i) => i % Math.ceil(dets.length / MAX_OVERLAY) === 0)
        : dets
      const r = 6
      sample.forEach(d => {
        const dot = document.createElement("div")
        dot.dataset.detection = "1"
        dot.style.width = "10px"
        dot.style.height = "10px"
        dot.style.borderRadius = "50%"
        dot.style.background = d.cls === "eos" ? "rgba(255,80,80,0.7)" : "rgba(255,200,0,0.7)"
        dot.style.border = "1px solid white"
        v.addOverlay({
          element: dot,
          location: v.viewport.imageToViewportRectangle(d.cx - r, d.cy - r, r * 2, r * 2),
        })
      })
      setShowOverlay(true)
    } finally {
      setOverlayLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-900 text-white">
      <div className="p-3 bg-slate-800 flex items-center gap-4">
        <Link href="/dashboard" className="text-blue-300 text-sm">← К списку</Link>
        <div className="text-sm">
          {info ? `${info.width}×${info.height} px` : ""}
          {info?.mppX ? ` • MPP=${info.mppX.toFixed(3)} µm/px` : ""}
        </div>
        {detections && (
          <>
            <div className="text-sm">
              Эозинофилов: <b>{detections.totalEosinophils}</b>
              {" • HPF max: "}
              <span className={detections.maxHpfCount >= 15 ? "text-red-400 font-bold" : "text-green-400"}>
                {detections.maxHpfCount}
              </span>
              {" • "}<span className="text-yellow-300">{detections.diagnosis}</span>
            </div>
            <button onClick={zoomToHpf}
                    className="ml-auto px-3 py-1 bg-red-700 rounded text-sm">
              К HPF max
            </button>
            <button onClick={toggleOverlay} disabled={overlayLoading}
                    className="px-3 py-1 bg-slate-700 rounded text-sm">
              {overlayLoading ? "Загрузка..." : showOverlay ? "Скрыть детекции" : "Показать детекции"}
            </button>
          </>
        )}
      </div>
      <div ref={containerRef} className="w-full" style={{ height: "calc(100vh - 56px)" }} />
    </div>
  )
}
