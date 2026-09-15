/**
 * squarified treemap.
 *
 * 면적이 곧 값이다. 가로로 잘라 나가기만 하면(slice-and-dice) 작은 값이 실 같은
 * 띠가 되어 앨범아트가 보이지 않는다. squarify 는 줄마다 "가장 납작한 타일의
 * 종횡비" 가 나빠지기 직전까지만 담아 타일을 정사각형에 가깝게 유지한다.
 *
 * 컴포넌트에서 떼어 둔 것은 순수 함수라 테스트하기 쉽기 때문이다. 좌표가 겹치거나
 * 면적이 값과 어긋나는 것은 눈으로 보고 잡기 어렵다.
 *
 * 참고: Bruls, Huizing, van Wijk — "Squarified Treemaps" (2000)
 */

/** 백분율 좌표. 컨테이너 크기를 몰라도 되고 반응형에 그대로 맞는다. */
export type TreemapRect = { x: number; y: number; w: number; h: number };

/**
 * 값 목록을 100×100 영역에 배치한다. 결과는 입력과 같은 순서다.
 *
 * **입력은 내림차순이어야 한다.** 섞여 있으면 큰 타일이 흩어져 읽기 어려워진다.
 * 0 이하인 값은 1 로 올린다 — 면적 0 인 타일은 클릭할 수 없다.
 */
export function squarify(values: number[]): TreemapRect[] {
  const safe = values.map((v) => (Number.isFinite(v) && v > 0 ? v : 1));
  const total = safe.reduce((sum, v) => sum + v, 0);
  if (!safe.length || !total) return [];

  const rects: TreemapRect[] = [];
  // 남은 영역과, 그 영역에 담아야 할 값의 합.
  let x = 0;
  let y = 0;
  let width = 100;
  let height = 100;
  let remaining = total;
  let index = 0;

  while (index < safe.length && remaining > 0) {
    const short = Math.min(width, height);
    const area = width * height;
    if (short <= 0 || area <= 0) break;

    // 이 줄에 담을 개수를 하나씩 늘려 보며 종횡비가 나빠지는 순간 멈춘다.
    let rowSum = 0;
    let count = 0;
    let best = Infinity;
    const rowAreas: number[] = [];
    while (index + count < safe.length) {
      const cell = (safe[index + count] / remaining) * area;
      const ratio = worstRatio([...rowAreas, cell], rowSum + cell, short);
      if (count > 0 && ratio > best) break;
      best = ratio;
      rowAreas.push(cell);
      rowSum += cell;
      count++;
    }

    // 줄을 좌표로 펼친다. 남은 영역의 긴 변을 따라 쌓는다.
    const horizontal = width >= height;
    const thickness = rowSum / short;
    let offset = 0;
    for (let i = 0; i < count; i++) {
      const length = rowAreas[i] / thickness;
      rects.push({
        x: horizontal ? x : x + offset,
        y: horizontal ? y + offset : y,
        w: horizontal ? thickness : length,
        h: horizontal ? length : thickness,
      });
      offset += length;
    }

    if (horizontal) {
      x += thickness;
      width -= thickness;
    } else {
      y += thickness;
      height -= thickness;
    }
    remaining -= safe.slice(index, index + count).reduce((sum, v) => sum + v, 0);
    index += count;
  }
  return rects;
}

/**
 * 줄 안에서 가장 나쁜 종횡비. 1 에 가까울수록 정사각형이다.
 *
 * `worst(R, w) = max( w²·rmax / s² , s² / (w²·rmin) )` — 논문 그대로다.
 * `w` 는 **남은 영역의 짧은 변**이고 줄 두께(`s / w`)가 아니다. 줄 두께를 넣으면
 * 두 항이 개수에 무관한 상수가 되어 어떤 줄도 끊기지 않고 전부 한 줄에 들어간다.
 */
function worstRatio(areas: number[], rowSum: number, short: number): number {
  if (!rowSum || !short) return Infinity;
  const max = Math.max(...areas);
  const min = Math.min(...areas);
  const span = short * short;
  return Math.max((span * max) / (rowSum * rowSum), (rowSum * rowSum) / (span * min));
}
