/**
 * 고정 크기 링버퍼.
 *
 * 네트워크에서 받은 청크 바이트를 여기에 채우고, 디코더가 하나씩 꺼내
 * PCM 으로 바꾼다. 수신과 디코딩 속도가 다르므로 그 사이의 완충 역할을 한다.
 *
 * 가득 차면 **가장 오래된 것을 버리지 않고 밀어넣기를 거절한다.** 오디오는
 * 순서가 곧 내용이라 중간을 버리면 소리가 튄다. 수신 쪽이 기다리는 게 맞다.
 */

export class RingBuffer<T> {
  private readonly slots: (T | undefined)[];
  private head = 0;
  private tail = 0;
  private count = 0;

  constructor(readonly capacity: number) {
    if (capacity < 1) throw new Error('링버퍼 용량은 1 이상이어야 합니다.');
    this.slots = new Array<T | undefined>(capacity);
  }

  get size() {
    return this.count;
  }

  get isFull() {
    return this.count === this.capacity;
  }

  get isEmpty() {
    return this.count === 0;
  }

  /** 넣었으면 true, 가득 차 거절했으면 false. */
  push(value: T): boolean {
    if (this.isFull) return false;
    this.slots[this.tail] = value;
    this.tail = (this.tail + 1) % this.capacity;
    this.count += 1;
    return true;
  }

  shift(): T | undefined {
    if (this.isEmpty) return undefined;
    const value = this.slots[this.head];
    this.slots[this.head] = undefined;
    this.head = (this.head + 1) % this.capacity;
    this.count -= 1;
    return value;
  }

  peek(): T | undefined {
    return this.isEmpty ? undefined : this.slots[this.head];
  }

  clear() {
    this.slots.fill(undefined);
    this.head = 0;
    this.tail = 0;
    this.count = 0;
  }

  toArray(): T[] {
    const values: T[] = [];
    for (let i = 0; i < this.count; i += 1) {
      values.push(this.slots[(this.head + i) % this.capacity] as T);
    }
    return values;
  }
}
