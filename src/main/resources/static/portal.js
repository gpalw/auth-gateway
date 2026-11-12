(() => {
  const search = document.querySelector('#search'), apps = [...document.querySelectorAll('.app')];
  const filters = document.querySelector('#filters'); let selected = '';
  const update = () => {
    const query = search.value.trim().toLocaleLowerCase(); let count = 0;
    for (const app of apps) {
      app.hidden = Boolean((selected && app.dataset.category !== selected) || !app.textContent.toLocaleLowerCase().includes(query));
      if (!app.hidden) count++;
    }
    document.querySelector('#count').textContent = `${count} 个入口`;
    document.querySelector('#empty').hidden = count !== 0;
  };
  for (const category of [...new Set(apps.map(a => a.dataset.category))]) {
    const button = document.createElement('button'); button.type = 'button'; button.dataset.category = category;
    button.textContent = category; button.setAttribute('aria-pressed', 'false'); filters.append(button);
  }
  filters.addEventListener('click', event => {
    const button = event.target.closest('button'); if (!button) return;
    selected = button.dataset.category;
    for (const item of filters.children) item.setAttribute('aria-pressed', String(item === button));
    update();
  });
  search.addEventListener('input', update);
  document.addEventListener('keydown', event => {
    if (event.key === '/' && !/INPUT|TEXTAREA/.test(document.activeElement.tagName)) { event.preventDefault(); search.focus(); }
    if (event.key === 'Escape' && document.activeElement === search) { search.value = ''; update(); }
  }); update();
})();
