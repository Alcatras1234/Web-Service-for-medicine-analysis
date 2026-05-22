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
  jobId: string
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
  const hpfGaugeRef = useRef<HTMLDivElement | null>(null)
  const [showHpfGauge, setShowHpfGauge] = useState(false)

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
        navigatorPosition: "TOP_RIGHT",
        navigatorWidth: 220,
        navigatorHeight: 220,
        navigatorBackground: "#000",
        navigatorBorderColor: "#444",
        navigatorDisplayRegionColor: "#00cc44",
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

      // Подсветка HPF max — пульсирующая ЗЕЛЁНАЯ рамка + автоцентр
      if (d && d.maxHpfCount > 0 && i.mppX) {
        const win = Math.round(Math.sqrt(0.3) * 1000 / i.mppX)
        const overlay = document.createElement("div")
        overlay.style.border = "4px solid #00cc44"
        overlay.style.boxShadow = "0 0 18px rgba(0,200,70,0.85), inset 0 0 12px rgba(0,200,70,0.35)"
        overlay.style.animation = "hpf-pulse 1.6s ease-in-out infinite"
        overlay.title = `HPF max: ${d.maxHpfCount} эозинофилов`

        if (!document.getElementById("hpf-pulse-style")) {
          const style = document.createElement("style")
          style.id = "hpf-pulse-style"
          style.textContent = `
            @keyframes hpf-pulse {
              0%, 100% { box-shadow: 0 0 18px rgba(0,200,70,0.85), inset 0 0 12px rgba(0,200,70,0.35); }
              50%      { box-shadow: 0 0 32px rgba(0,220,80,1.0),  inset 0 0 22px rgba(0,200,70,0.55); }
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
              dets.forEach((dt: any) => {
                // Bbox вокруг каждой клетки — реальные размеры из x1,y1,x2,y2
                const x1 = dt.x1 ?? (dt.cx - 10)
                const y1 = dt.y1 ?? (dt.cy - 10)
                const x2 = dt.x2 ?? (dt.cx + 10)
                const y2 = dt.y2 ?? (dt.cy + 10)
                const w = Math.max(1, x2 - x1)
                const h = Math.max(1, y2 - y1)

                const box = document.createElement("div")
                box.dataset.detection = "1"
                const isIntact = dt.cls === "eos"
                box.style.boxSizing = "border-box"
                box.style.border = isIntact
                  ? "2px solid rgba(255,40,40,0.95)"
                  : "2px solid rgba(255,200,0,0.95)"
                box.style.background = isIntact
                  ? "rgba(255,40,40,0.12)"
                  : "rgba(255,200,0,0.12)"
                box.style.borderRadius = "2px"
                box.style.cursor = "help"
                const conf = typeof dt.conf === "number" ? dt.conf.toFixed(2) : "—"
                const cl = isIntact ? "intact" : (dt.cls === "eosg" ? "granulated" : dt.cls)
                box.title = `${cl}  ·  conf ${conf}  ·  bbox ${Math.round(w)}×${Math.round(h)} px`

                viewerRef.current.addOverlay({
                  element: box,
                  location: viewerRef.current.viewport.imageToViewportRectangle(x1, y1, w, h),
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

  function toggleHpfGauge() {
    const v = viewerRef.current
    if (!v || !info?.mppX) return

    if (showHpfGauge && hpfGaugeRef.current) {
      v.removeOverlay(hpfGaugeRef.current)
      hpfGaugeRef.current = null
      setShowHpfGauge(false)
      return
    }

    // 0.3 мм² → сторона √0.3 мм × 1000 / mpp = пиксели на WSI
    const sidePx = Math.round(Math.sqrt(0.3) * 1000 / info.mppX)
    // Центрируем в текущем viewport
    const center = v.viewport.getCenter()
    const centerImg = v.viewport.viewportToImageCoordinates(center)

    const box = document.createElement("div")
    box.style.border = "3px dashed #00cc44"
    box.style.background = "rgba(0,200,70,0.10)"
    box.style.boxShadow = "0 0 12px rgba(0,200,70,0.5)"
    box.style.pointerEvents = "none"
    box.style.boxSizing = "border-box"
    // Подпись внутри
    const label = document.createElement("div")
    label.textContent = `0.3 мм² (${sidePx} × ${sidePx} px)`
    label.style.position = "absolute"
    label.style.top = "4px"
    label.style.left = "4px"
    label.style.padding = "2px 6px"
    label.style.background = "rgba(0,200,70,0.9)"
    label.style.color = "white"
    label.style.fontSize = "11px"
    label.style.fontWeight = "bold"
    label.style.borderRadius = "3px"
    box.appendChild(label)

    hpfGaugeRef.current = box
    v.addOverlay({
      element: box,
      location: v.viewport.imageToViewportRectangle(
        centerImg.x - sidePx / 2,
        centerImg.y - sidePx / 2,
        sidePx, sidePx
      ),
    })
    setShowHpfGauge(true)
  }

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
      sample.forEach((d: any) => {
        const x1 = d.x1 ?? (d.cx - 10)
        const y1 = d.y1 ?? (d.cy - 10)
        const x2 = d.x2 ?? (d.cx + 10)
        const y2 = d.y2 ?? (d.cy + 10)
        const w = Math.max(1, x2 - x1)
        const h = Math.max(1, y2 - y1)

        const box = document.createElement("div")
        box.dataset.detection = "1"
        const isIntact = d.cls === "eos"
        box.style.boxSizing = "border-box"
        box.style.border = isIntact
          ? "2px solid rgba(255,40,40,0.95)"
          : "2px solid rgba(255,200,0,0.95)"
        box.style.background = isIntact
          ? "rgba(255,40,40,0.10)"
          : "rgba(255,200,0,0.10)"
        box.style.borderRadius = "2px"
        box.style.cursor = "help"
        const conf = typeof d.conf === "number" ? d.conf.toFixed(2) : "—"
        const cl = isIntact ? "intact" : (d.cls === "eosg" ? "granulated" : d.cls)
        box.title = `${cl}  ·  conf ${conf}  ·  bbox ${Math.round(w)}×${Math.round(h)} px`
        v.addOverlay({
          element: box,
          location: v.viewport.imageToViewportRectangle(x1, y1, w, h),
        })
      })
      setShowOverlay(true)
    } finally {
      setOverlayLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-900 text-white">
      <div className="p-3 bg-slate-800 flex items-center gap-2 flex-wrap">
        <Link href="/dashboard" className="text-slate-300 hover:text-white px-2 py-1 rounded hover:bg-slate-700 text-sm">
          ← Дашборд
        </Link>
        <span className="text-slate-500">/</span>
        <Link href="/cases" className="text-slate-300 hover:text-white px-2 py-1 rounded hover:bg-slate-700 text-sm">
          Кейсы
        </Link>
        <span className="text-slate-500">/</span>
        <Link href={`/cases/${params.id}`} className="text-slate-300 hover:text-white px-2 py-1 rounded hover:bg-slate-700 text-sm">
          Кейс #{params.id}
        </Link>
        <span className="text-slate-500">/</span>
        <span className="text-white text-sm font-medium">Слайд #{slideId}</span>
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
            <button onClick={toggleHpfGauge}
                    className="px-3 py-1 bg-green-700 rounded text-sm">
              {showHpfGauge ? "Скрыть 0.3 мм²" : "Показать 0.3 мм²"}
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
