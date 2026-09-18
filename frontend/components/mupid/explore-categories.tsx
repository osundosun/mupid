'use client';
import { useState } from 'react';
import { Info, SlidersHorizontal, Music2, Mic2, RotateCcw, X } from 'lucide-react';
import type { ExploreFilterOptions } from '@/lib/api/endpoints';
import { toggleExploreFilter } from '@/lib/explore-filter';

type Option = { key: string; label: string };
type Category = { title: string; icon: typeof SlidersHorizontal; options: Option[] };

function categoriesOf(options: ExploreFilterOptions): Category[] {
  return [
    { title: '분위기', icon: SlidersHorizontal, options: options.moods.map((item) => ({ key: `mood:${item.name}`, label: item.display_name || item.name })) },
    { title: '장르', icon: Music2, options: options.genres.map((item) => ({ key: `genre:${item.name}`, label: item.display_name || item.name })) },
    { title: '보컬', icon: Mic2, options: options.vocals.map((item) => ({ key: `vocal:${item.value}`, label: item.display_name })) },
  ];
}

export function ExploreCategories({ options, loading, error, selected, onChange: setSelected, onSearch, onReset }: {
  options: ExploreFilterOptions;
  loading: boolean;
  error: string;
  selected: string[];
  onChange: (values: string[]) => void;
  onSearch: () => void;
  onReset: () => void;
}) {
  const [active, setActive] = useState(0);
  const categories = categoriesOf(options);
  const labels = new Map(categories.flatMap((category) => category.options.map((option) => [option.key, option.label] as const)));
  const toggle = (key: string) => setSelected(toggleExploreFilter(selected, key));
  const category = categories[active];

  return <section className="explore-category-card" aria-label="음악 취향 선택">
    <div className="explore-category-heading"><div><h2>어떤 음악을 만나볼까요?</h2></div>
      <button className="category-reset" disabled={!selected.length} onClick={() => { setSelected([]); onReset(); }}><RotateCcw size={15} /> 초기화</button>
    </div>
    <div className="explore-category-switch" aria-label="큰 분류">
      {categories.map(({ title, icon: Icon }, index) => <button key={title} aria-pressed={index === active} onClick={() => setActive(index)}><Icon size={20} /><span>{title}</span></button>)}
    </div>
    <section className="category-options-scroll" aria-label="검색 조건">
      <p className="category-description category-description--hint"><Info size={15} aria-hidden="true" />{category.title === '보컬' ? '하나만 선택할 수 있어요' : '여러 개 선택할 수 있어요'}</p>
      {loading ? <output className="category-description">선택지를 불러오고 있어요.</output> : error ? <p className="category-description" role="alert">{error}</p> :
        <div className="category-groups"><fieldset><legend>{category.title}</legend><div>
          {category.options.map((option) => <button type="button" key={option.key} aria-pressed={selected.includes(option.key)} onClick={() => toggle(option.key)}>{option.label}</button>)}
        </div></fieldset></div>}
      {selected.length > 0 && <div className="category-selected" aria-label="선택한 조건"><span>{selected.length}개 선택</span>{selected.map((key) => <button key={key} aria-label={`${labels.get(key) ?? key} 선택 해제`} onClick={() => toggle(key)}>{labels.get(key) ?? key}<X size={13} /></button>)}</div>}
    </section>
    <div className="category-search-action"><button type="button" className="primary-button" disabled={loading || Boolean(error)} onClick={onSearch}>검색하기</button></div>
  </section>;
}
