(() => {
  const header = document.querySelector('[data-header]');
  const menuToggle = document.querySelector('.menu-toggle');
  const nav = document.querySelector('#site-nav');
  const navLinks = nav ? nav.querySelectorAll('a') : [];

  const updateHeader = () => {
    if (header) header.classList.toggle('is-scrolled', window.scrollY > 16);
  };
  updateHeader();
  window.addEventListener('scroll', updateHeader, { passive: true });

  const closeMenu = () => {
    if (!menuToggle || !nav) return;
    nav.classList.remove('is-open');
    menuToggle.setAttribute('aria-expanded', 'false');
    document.body.classList.remove('menu-open');
  };

  if (menuToggle && nav) {
    menuToggle.addEventListener('click', () => {
      const open = menuToggle.getAttribute('aria-expanded') === 'true';
      menuToggle.setAttribute('aria-expanded', String(!open));
      nav.classList.toggle('is-open', !open);
      document.body.classList.toggle('menu-open', !open);
    });
    navLinks.forEach((link) => link.addEventListener('click', closeMenu));
    window.addEventListener('resize', () => {
      if (window.innerWidth > 680) closeMenu();
    });
  }

  const comparison = document.querySelector('[data-comparison]');
  const beforeWrap = document.querySelector('[data-before-wrap]');
  const beforeImage = document.querySelector('.comparison-before');
  const divider = document.querySelector('[data-divider]');
  const range = document.querySelector('[data-range]');

  const resizeComparison = () => {
    if (!comparison || !beforeImage) return;
    beforeImage.style.width = `${comparison.getBoundingClientRect().width}px`;
  };

  const updateComparison = () => {
    if (!range || !beforeWrap || !divider) return;
    const value = Number(range.value);
    beforeWrap.style.width = `${value}%`;
    divider.style.left = `${value}%`;
    range.setAttribute('aria-valuetext', `${value} percent original image`);
  };

  if (range) {
    resizeComparison();
    updateComparison();
    range.addEventListener('input', updateComparison);
    window.addEventListener('resize', resizeComparison);
    window.addEventListener('load', resizeComparison);
  }
})();
