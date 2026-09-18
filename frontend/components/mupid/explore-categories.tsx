'use client';
import { useState } from 'react';
import { SlidersHorizontal, Music2, Mic2, RotateCcw, X } from 'lucide-react';

// Display taxonomy is independent from demo track metadata. API filtering can
// consume these selections once the analysis labels are available.
const categories = [
  { title: '분위기', icon: SlidersHorizontal, description: '지금 마음에 어울리는 음악', groups: [
    { title: '무드', options: ['행복한', '묵직한', '사랑스러운', '명상적인', '선율적인', '편안한', '어두운', '활기찬', '영감을 주는', '의욕적인'] },
  ] },
  { title: '장르', icon: Music2, description: '익숙한 취향부터 새로운 장르까지', groups: [
    { title: '장르', options: ['Pop', 'Electronic', 'R&B / Soul', 'Rock', 'Folk', 'Classical'] },
  ] },
  { title: '보컬·악기', icon: Mic2, description: '좋아하는 목소리와 소리를 찾아서', groups: [
    { title: '보컬', options: ['여성 보컬', '남성 보컬', '인스트루멘탈'] },
    { title: '기타', options: ['어쿠스틱 기타', '일렉 기타', '기타'] },
    { title: '건반', options: ['피아노', '키보드', '신디사이저'] },
    { title: '리듬', options: ['드럼'] },
  ] },
];

export function ExploreCategories({ selected, onChange: setSelected, onSearch }: { selected: string[]; onChange: (values: string[]) => void; onSearch: () => void }) {
  const [active, setActive] = useState(0);
  const toggle = (key: string) => setSelected(selected.includes(key) ? selected.filter(value => value !== key) : [...selected, key]);
  return <section className="explore-category-card" aria-label="음악 취향 선택">
    <div className="explore-category-heading"><div><h2>어떤 음악을 만나볼까요?</h2></div>
      <button className="category-reset" disabled={!selected.length} onClick={() => setSelected([])}><RotateCcw size={15} /> 초기화</button>
    </div>
    <div className="explore-category-switch" aria-label="큰 분류">
      {categories.map(({title, icon: Icon}, index) => <button key={title} aria-pressed={index === active} onClick={() => setActive(index)}><Icon size={20} /><span>{title}</span></button>)}
    </div>
    <div className="category-options-scroll" tabIndex={0} role="region" aria-label="검색 조건">
    <p className="category-description">여러 개 선택할 수 있어요</p>
    <div className="category-groups">
      {categories[active].groups.map(group => <fieldset key={`${active}-${group.title}`}><legend>{group.title}</legend><div>
        {group.options.map(option => { const key = `${categories[active].title}/${group.title}/${option}`; return <button type="button" key={key} aria-pressed={selected.includes(key)} onClick={() => toggle(key)}>{option}</button>; })}
      </div></fieldset>)}
    </div>
    {selected.length > 0 && <div className="category-selected" aria-label="선택한 조건"><span>{selected.length}개 선택</span>{selected.map(key => <button key={key} aria-label={`${key.split('/').at(-1)} 선택 해제`} onClick={() => toggle(key)}>{key.split('/').at(-1)}<X size={13} /></button>)}</div>}
    </div>
    <div className="category-search-action"><button type="button" className="primary-button" onClick={onSearch}>검색하기</button></div>
  </section>;
}
