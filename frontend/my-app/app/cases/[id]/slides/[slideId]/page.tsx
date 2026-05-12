"use client"

import { useEffect, useRef, useState } from "react"
import { useParams } from "next/navigation"
import Link from "next/link"
import { attachScaleBar } from "@/components/viewer/ScaleBar"

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

export default function SlideViewerPage() {
  const params = useParams<{ id: string; slideId: string }>()
  const slideId = params.slideId
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<any>(null)
  const [info, setInfo] = useState<SlideInfo | null>(null)
  const [detections, setDetections] = useState<Detections | null>(null)
  const [showOverlay, setShowOverlay] = useState(false)
  const [overlayLoading, setOverlayLoading] = useState(false)

  // Загружаем info + detections + инициализируем OpenSeadragon
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

      // Динамический import — OSD только в браузере
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

      // §5.3: масштабная линейка
      const detachScale = attachScaleBar(viewerRef.current, i.mppX, containerRef.current)
      ;(viewerRef.current as any).__detachScale = detachScale

      // Подсветка HPF max — пульсирующая красная рамка + автоцентр
      if (d && d.maxHpfCount > 0 && i.mppX) {
        const win = Math.round(Math.sqrt(0.3) * 1000 / i.mppX)
        const overlay = document.createElement("div")
        overlay.style.border = "4px solid #ff2030"
        overlay.style.boxShadow = "0 0 18px rgba(255,30,40,0.85), inset 0 0 12px rgba(255,30,40,0.4)"
        overlay.style.animation = "hpf-pulse 1.6s ease-in-out infinite"
        overlay.title = `HPF max: ${d.maxHpfCount} эозинофилов`

        if (!document.getElementById("hpf-pulse-style")) {
          const style = document.createElement("style")
          style.id = "hpf-pulse-style"
          style.textContent = `
            @keyframes hpf-pulse {
              0%, 100% { box-shadow: 0 0 18px rgba(255,30,40,0.85), inset 0 0 12px rgba(255,30,40,0.4); }
              50%      { box-shadow: 0 0 28px rgba(255,30,40,1.0),  inset 0 0 20px rgba(255,30,40,0.6); }
            }
          `
          document.head.appendChild(style)
        }

        viewerRef.current.addOverlay({
          element: overlay,
          location: viewerRef.current.viewport.imageToViewportRectangle(
            d.maxHpfX, d.maxHpfY, win, win
          ),
        })

        viewerRef.current.addOnceHandler("open", () => {
          const rect = viewerRef.current.viewport.imageToViewportRectangle(
            d.maxHpfX - win, d.maxHpfY - win, win * 3, win * 3
          )
          viewerRef.current.viewport.fitBounds(rect, true)
        })

        try {
          const dRes = await fetch(`/api/slides/${slideId}/detections/full`)
          if (dRes.ok) {
            const dData = await dRes.json()
            const dets = dData.detections || []
            if (dets.length > 0 && dets.length < 5000) {
              const r = 6
              dets.forEach((dt: any) => {
                const dot = document.createElement("div")
                dot.dataset.detection = "1"
                dot.style.width = "10px"
                dot.style.height = "10px"
                dot.style.borderRadius = "50%"
                dot.style.background = dt.cls === "eos" ? "rgba(255,80,80,0.8)" : "rgba(255,200,0,0.8)"
                dot.style.border = "1px solid white"
                dot.style.cursor = "help"
                const conf = typeof dt.conf === "number" ? dt.conf.toFixed(2) : "—"
                const cl = dt.cls === "eos" ? "intact" : (dt.cls === "eosg" ? "granulated" : dt.cls)
                dot.title = `${cl}  ·  conf ${conf}  ·  (${Math.round(dt.cx)}, ${Math.round(dt.cy)}) px`
                viewerRef.current.addOverlay({
                  element: dot,
                  location: viewerRef.current.viewport.imageToViewportRectangle(dt.cx - r, dt.cy - r, r * 2, r * 2),
                })
              })
              setShowOverlay(true)
            }
          }
        } catch {}
      }
    }

    init()
    return () => {
      cancelled = true
      try { (viewerRef.current as any)?.__detachScale?.() } catch {}
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

  // E6: загрузка/отображение всех координат детекций как overlay-точек
  async function toggleOverlay() {
    const v = viewerRef.current
    if (!v) return
    if (showOverlay) {
      // снимаем все detection-overlays
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
      // ограничим число точек, чтобы не убить DOM на 5М детекций
      const MAX_OVERLAY = 5000
      const sample = dets.length > MAX_OVERLAY
        ? dets.filter((_, i) => i % Math.ceil(dets.length / MAX_OVERLAY) === 0)
        : dets
      const r = 6 // радиус кружка в пикселях изображения
      sample.forEach((d: any) => {
        const dot = document.createElement("div")
        dot.dataset.detection = "1"
        dot.style.width = "10px"
        dot.style.height = "10px"
        dot.style.borderRadius = "50%"
        dot.style.background = d.cls === "eos" ? "rgba(255,80,80,0.7)" : "rgba(255,200,0,0.7)"
        dot.style.border = "1px solid white"
        dot.style.cursor = "help"
        const conf = typeof d.conf === "number" ? d.conf.toFixed(2) : "—"
        const cl = d.cls === "eos" ? "intact" : (d.cls === "eosg" ? "granulated" : d.cls)
        dot.title = `${cl}  ·  conf ${conf}  ·  (${Math.round(d.cx)}, ${Math.round(d.cy)}) px`
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
        <Link href={`/cases/${params.id}`} className="text-blue-300 text-sm">← К кейсу</Link>
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
