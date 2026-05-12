/**
 * §5.3: масштабная линейка для OpenSeadragon.
 * Привязывается к viewer'у, обновляется на zoom-event'ах. Подбирает «круглую» длину
 * (10/20/50/100/200/500 µm, 1/2/5 мм) так, чтобы линейка занимала ~15-30% ширины viewer'а.
 *
 * Использование:
 *   const detach = attachScaleBar(viewer, mppX, containerEl)
 *   // при unmount: detach()
 */
export function attachScaleBar(
  viewer: any,
  mppX: number | null | undefined,
  container: HTMLElement,
): () => void {
  if (!viewer || !mppX || mppX <= 0) return () => {}

  // Контейнер для bar'а
  const bar = document.createElement("div")
  bar.style.position = "absolute"
  bar.style.right = "16px"
  bar.style.bottom = "16px"
  bar.style.padding = "6px 10px"
  bar.style.borderRadius = "6px"
  bar.style.background = "rgba(0,0,0,0.62)"
  bar.style.color = "white"
  bar.style.fontFamily = "system-ui, sans-serif"
  bar.style.fontSize = "12px"
  bar.style.fontWeight = "600"
  bar.style.display = "flex"
  bar.style.flexDirection = "column"
  bar.style.alignItems = "center"
  bar.style.gap = "4px"
  bar.style.pointerEvents = "none"
  bar.style.zIndex = "20"
  bar.dataset.scalebar = "1"

  const line = document.createElement("div")
  line.style.height = "5px"
  line.style.background = "white"
  line.style.border = "1px solid black"
  line.style.boxShadow = "0 0 0 1px rgba(255,255,255,0.4)"
  line.style.borderRadius = "1px"
  bar.appendChild(line)

  const label = document.createElement("span")
  bar.appendChild(label)

  container.appendChild(bar)

  function update() {
    if (!viewer.world?.getItemAt(0)) return
    const containerWidth = viewer.container.clientWidth
    if (containerWidth === 0) return

    // Сколько пикселей изображения в одном CSS-пикселе экрана
    const imagePixelsPerScreenPixel =
      viewer.world.getItemAt(0).getContentSize().x /
      viewer.world.getItemAt(0).getBounds().width /
      viewer.viewport.getZoom(true) /
      containerWidth
    // umPerScreenPx
    const umPerPx = mppX! * imagePixelsPerScreenPixel

    // Целевая длина 15–30% ширины viewer'а
    const targetMin = containerWidth * 0.15
    const targetMax = containerWidth * 0.30

    const candidatesUm = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000]
    let chosenUm = candidatesUm[0]
    for (const c of candidatesUm) {
      const px = c / umPerPx
      if (px > targetMin && px < targetMax) { chosenUm = c; break }
      if (px <= targetMin) chosenUm = c     // запоминаем последнее «маленькое»
    }
    const barPx = chosenUm / umPerPx

    line.style.width = `${Math.max(20, Math.round(barPx))}px`
    label.textContent = chosenUm >= 1000 ? `${chosenUm / 1000} мм` : `${chosenUm} µm`
  }

  // Реагируем на zoom / resize
  const handler = () => requestAnimationFrame(update)
  viewer.addHandler("zoom", handler)
  viewer.addHandler("resize", handler)
  viewer.addHandler("open", handler)
  setTimeout(update, 100)   // первичный рендер

  return () => {
    try {
      viewer.removeHandler("zoom", handler)
      viewer.removeHandler("resize", handler)
      viewer.removeHandler("open", handler)
    } catch {}
    if (bar.parentElement) bar.parentElement.removeChild(bar)
  }
}
