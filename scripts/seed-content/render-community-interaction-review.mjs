import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { lstat, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const requireFromWeb = createRequire(path.join(repoRoot, 'apps', 'web', 'package.json'));
const YAML = requireFromWeb('yaml');

const packRoot = path.resolve(process.argv[2] ?? path.join(repoRoot, 'content', 'seed', 'content-v3'));
const outputFile = path.resolve(
  process.argv[3] ?? path.join(repoRoot, '.codex-tmp', 'community-interactions', 'review', 'index.html'),
);
await assertIgnoredProjectOutput(outputFile);

const [accounts, assets, posts, loadedInteractions] = await Promise.all([
  loadYaml('accounts.yml'),
  loadYaml('assets.yml'),
  loadYaml('posts.yml'),
  loadYaml('interactions.yml'),
]);
const interactions = {
  comments: loadedInteractions.comments ?? [],
  reactions: loadedInteractions.reactions ?? [],
  follows: loadedInteractions.follows ?? [],
  views: loadedInteractions.views ?? [],
};

const accountByKey = new Map(accounts.map((account) => [account.seedKey, account]));
const assetByKey = new Map(assets.map((asset) => [asset.key, asset]));
const postByKey = new Map(posts.map((post) => [post.seedKey, post]));
const commentByKey = new Map(interactions.comments.map((comment) => [comment.seedKey, comment]));
const roots = interactions.comments.filter((comment) => !comment.parentSeedKey).length;
const replies = interactions.comments.length - roots;
const likes = interactions.reactions.filter((reaction) => reaction.type === 'like').length;
const favorites = interactions.reactions.filter((reaction) => reaction.type === 'fav').length;
const targetStats = aggregateTargets(interactions, posts);
const sortedTargetStats = [...targetStats.values()].sort(compareTargetStats);
const zeroCommentTargets = sortedTargetStats.filter((stat) => stat.comments === 0);
const commentedTargetCount = sortedTargetStats.length - zeroCommentTargets.length;
const accountStats = aggregateAccounts(accounts, interactions);
const timeStats = aggregateCommentTimes(interactions.comments);

const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Chtholly Hub 社区互动包审阅</title>
  <style>
    :root { color-scheme:light; --ink:#25314a; --muted:#6e7890; --line:#dfe5ef; --paper:#fbfcff; --blue:#4c83b9; --rose:#bc7289; --warm:#f6ede8; }
    * { box-sizing:border-box; }
    body { margin:0; color:var(--ink); font:15px/1.75 "Segoe UI","Microsoft YaHei",sans-serif; background:linear-gradient(135deg,#f5f8fd,#fff8f4 55%,#f6f1fb); }
    main { width:min(1180px,calc(100% - 32px)); margin:32px auto 80px; }
    header,.panel { background:rgba(255,255,255,.9); border:1px solid rgba(212,220,234,.9); border-radius:22px; box-shadow:0 18px 55px rgba(76,92,126,.10); }
    header { padding:34px 38px; }
    h1,h2,h3,p { margin-top:0; }
    h1 { margin-bottom:8px; font-size:30px; letter-spacing:.02em; }
    h2 { margin-bottom:18px; font-size:22px; }
    h3 { margin-bottom:10px; font-size:16px; }
    .muted { color:var(--muted); }
    .summary { display:grid; grid-template-columns:repeat(4,1fr); gap:12px; margin-top:26px; }
    .metric { padding:13px 12px; border-radius:15px; background:#f5f8fc; text-align:center; }
    .metric span { display:block; color:var(--muted); font-size:12px; }
    .metric strong { display:block; color:var(--blue); font-size:23px; }
    .panel { margin-top:22px; padding:28px 30px; }
    .profiles { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; }
    .profile,.comment,.stat-row,.follow,.notice { border:1px solid var(--line); border-radius:16px; background:var(--paper); }
    .profile { padding:16px; }
    .profile-head { display:flex; gap:12px; align-items:center; margin-bottom:10px; }
    .avatar { width:52px; height:52px; border-radius:14px; object-fit:cover; background:#eaf0f7; }
    .handle,.time,.target { color:var(--muted); font-size:13px; }
    .tags { display:flex; flex-wrap:wrap; gap:6px; margin-top:10px; }
    .tag { padding:2px 9px; border-radius:999px; background:#eaf2fa; color:#426d96; font-size:12px; }
    .split { display:grid; grid-template-columns:1fr 1fr; gap:20px; }
    .stat-list,.follow-list,.month-list { display:grid; gap:8px; }
    .stat-row,.follow { padding:10px 13px; display:flex; justify-content:space-between; gap:14px; }
    .stat-row strong { text-align:right; }
    .follow { justify-content:flex-start; }
    .arrow { color:var(--rose); font-weight:700; }
    .notice { padding:13px 15px; background:var(--warm); }
    .toolbar { display:flex; flex-wrap:wrap; gap:12px; align-items:center; margin-bottom:16px; }
    select { border:1px solid var(--line); border-radius:10px; padding:8px 12px; background:white; color:var(--ink); }
    .comments { display:grid; gap:12px; }
    .comment { padding:17px 19px; }
    .comment.reply { margin-left:46px; border-left:4px solid #e3b4c2; }
    .comment-head { display:flex; flex-wrap:wrap; gap:8px 14px; align-items:baseline; margin-bottom:8px; }
    .comment p { margin:0; font-size:15px; }
    .reply-to { color:var(--rose); }
    @media (max-width:900px) { .summary { grid-template-columns:repeat(2,1fr); } .profiles { grid-template-columns:repeat(2,1fr); } .split { grid-template-columns:1fr; } }
    @media (max-width:560px) { main { width:min(100% - 18px,1180px); margin-top:10px; } header,.panel { padding:20px 17px; border-radius:16px; } .profiles { grid-template-columns:1fr; } .comment.reply { margin-left:18px; } .stat-row { display:block; } .stat-row strong { display:block; text-align:left; } }
  </style>
</head>
<body>
<main>
  <header>
    <h1>社区互动包 · 导入前审阅</h1>
    <p class="muted">本页只读取 content-v3 文件，不连接数据库，也不会产生任何站内行为。评论逐条展示，其余互动按账号和文章核对。</p>
    <div class="summary">
      ${metric('账号', accounts.length)}${metric('总评论', interactions.comments.length)}
      ${metric('主评论', roots)}${metric('回复', replies)}
      ${metric('点赞', likes)}${metric('收藏', favorites)}
      ${metric('关注 / 浏览', `${interactions.follows.length} / ${interactions.views.length}`)}
      ${metric('评论覆盖', `${commentedTargetCount} / ${sortedTargetStats.length}`)}
    </div>
  </header>

  <section class="panel">
    <h2>账号资料</h2>
    <div class="profiles">${accounts.map(renderProfile).join('')}</div>
  </section>

  <section class="panel">
    <div class="split">
      <div><h2>账号参与分布</h2><div class="stat-list">${accountStats.map(renderAccountStat).join('')}</div></div>
      <div><h2>评论时间分布</h2>${renderTimeSummary(timeStats)}</div>
    </div>
  </section>

  <section class="panel">
    <h2>评论逐条审阅</h2>
    <div class="toolbar"><label for="author-filter">按账号筛选</label><select id="author-filter"><option value="">全部账号</option>${accounts.map((account) => `<option value="${escapeHtml(account.seedKey)}">${escapeHtml(account.nickname)}</option>`).join('')}</select><span id="visible-count" class="muted"></span></div>
    <div class="comments">${interactions.comments.map(renderComment).join('')}</div>
  </section>

  <section class="panel">
    <h2>文章互动覆盖</h2>
    <div class="split">
      <div><h3>逐文章热度</h3><div class="stat-list">${sortedTargetStats.map(renderTargetStat).join('')}</div></div>
      <div><h3>零评论目标（${zeroCommentTargets.length}）</h3>${zeroCommentTargets.length ? `<div class="stat-list">${zeroCommentTargets.map(renderZeroCommentTarget).join('')}</div>` : '<div class="notice">全部目标至少有一条评论。</div>'}</div>
    </div>
  </section>

  <section class="panel">
    <h2>关系与浏览基线</h2>
    <div class="split">
      <div><h3>关注关系</h3><div class="follow-list">${interactions.follows.map(renderFollow).join('')}</div></div>
      <div><h3>浏览目标</h3><div class="stat-list">${[...interactions.views].sort(compareViews).map(renderView).join('')}</div></div>
    </div>
  </section>
</main>
<script>
  const filter = document.querySelector('#author-filter');
  const rows = [...document.querySelectorAll('.comment')];
  const count = document.querySelector('#visible-count');
  function applyFilter() { let visible=0; rows.forEach((row) => { const show=!filter.value || row.dataset.author===filter.value; row.hidden=!show; if(show) visible++; }); count.textContent='当前显示 '+visible+' / '+rows.length+' 条'; }
  filter.addEventListener('change', applyFilter); applyFilter();
</script>
</body>
</html>`;

await mkdir(path.dirname(outputFile), { recursive: true });
await assertRealPathInsideRepository(path.dirname(outputFile));
await writeFile(outputFile, html, 'utf8');
console.log(`review=${outputFile}`);
console.log(`accounts=${accounts.length} comments=${interactions.comments.length} roots=${roots} replies=${replies} likes=${likes} favorites=${favorites} follows=${interactions.follows.length} views=${interactions.views.length} commentedTargets=${commentedTargetCount} targets=${sortedTargetStats.length}`);

async function loadYaml(file) {
  return YAML.parse(await readFile(path.join(packRoot, file), 'utf8'));
}

function aggregateTargets(values, postDefinitions) {
  const stats = new Map();
  const get = (value) => {
    const key = targetKey(value);
    const current = stats.get(key) ?? {
      target: key,
      comments: 0,
      roots: 0,
      replies: 0,
      like: 0,
      fav: 0,
      views: null,
    };
    stats.set(key, current);
    return current;
  };

  postDefinitions.forEach((post) => get({ postSeedKey: post.seedKey }));
  values.comments.forEach((comment) => {
    const stat = get(comment);
    stat.comments += 1;
    stat[comment.parentSeedKey ? 'replies' : 'roots'] += 1;
  });
  values.reactions.forEach((reaction) => {
    get(reaction)[reaction.type] += 1;
  });
  values.views.forEach((view) => {
    get(view).views = view.minimumCount;
  });
  return stats;
}

function aggregateAccounts(accountDefinitions, values) {
  const stats = new Map(accountDefinitions.map((account) => [account.seedKey, {
    account,
    comments: 0,
    like: 0,
    fav: 0,
    followOut: 0,
    followIn: 0,
  }]));
  values.comments.forEach((comment) => {
    if (stats.has(comment.authorSeedKey)) stats.get(comment.authorSeedKey).comments += 1;
  });
  values.reactions.forEach((reaction) => {
    if (stats.has(reaction.accountSeedKey)) stats.get(reaction.accountSeedKey)[reaction.type] += 1;
  });
  values.follows.forEach((follow) => {
    if (stats.has(follow.fromAccountSeedKey)) stats.get(follow.fromAccountSeedKey).followOut += 1;
    if (follow.toAccountSeedKey && stats.has(follow.toAccountSeedKey)) stats.get(follow.toAccountSeedKey).followIn += 1;
  });
  return [...stats.values()];
}

function aggregateCommentTimes(comments) {
  const datedComments = comments.map((comment) => {
    const date = new Date(comment.createdAt);
    if (Number.isNaN(date.getTime())) {
      throw new Error(`invalid comment timestamp: ${comment.seedKey}`);
    }
    return date;
  }).sort((left, right) => left.getTime() - right.getTime());
  const months = new Map();
  datedComments.forEach((date) => {
    const month = formatMonth(date);
    months.set(month, (months.get(month) ?? 0) + 1);
  });
  return {
    earliest: datedComments[0] ?? null,
    latest: datedComments.at(-1) ?? null,
    months: [...months.entries()].sort(([left], [right]) => left.localeCompare(right)),
  };
}

function targetKey(value) {
  return value.postSeedKey ? `seed:${value.postSeedKey}` : `slug:${value.postSlug}`;
}

function targetLabel(value) {
  return value.postSeedKey
    ? (postByKey.get(value.postSeedKey)?.title ?? value.postSeedKey)
    : `Rekyrice / ${value.postSlug}`;
}

function accountLabel(key) {
  const account = accountByKey.get(key);
  return account ? `${account.nickname} (@${account.handle})` : key;
}

function metric(label, value) {
  return `<div class="metric"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`;
}

function renderProfile(account) {
  const asset = assetByKey.get(account.avatarAsset);
  const source = asset
    ? `/content/seed/content-v3/assets/${asset.file.split('/').map(encodeURIComponent).join('/')}`
    : '';
  return `<article class="profile"><div class="profile-head">${source ? `<img class="avatar" src="${source}" alt="">` : ''}<div><h3>${escapeHtml(account.nickname)}</h3><div class="handle">@${escapeHtml(account.handle)} · 加入于 ${formatTime(account.joinedAt)}</div></div></div><p>${escapeHtml(account.bio)}</p><div class="tags">${(account.tags ?? []).map((tag) => `<span class="tag">${escapeHtml(tag)}</span>`).join('')}</div></article>`;
}

function renderAccountStat(stat) {
  return `<div class="stat-row"><span>${escapeHtml(accountLabel(stat.account.seedKey))}</span><strong>评 ${stat.comments} · 赞 ${stat.like} · 藏 ${stat.fav} · 关注 ${stat.followOut} / ${stat.followIn}</strong></div>`;
}

function renderTimeSummary(stats) {
  const range = stats.earliest
    ? `<div class="notice">最早 ${formatTime(stats.earliest)} · 最晚 ${formatTime(stats.latest)}</div>`
    : '<div class="notice">当前没有评论时间。</div>';
  const months = stats.months.length
    ? `<div class="month-list">${stats.months.map(([month, count]) => `<div class="stat-row"><span>${escapeHtml(month)}</span><strong>${count} 条</strong></div>`).join('')}</div>`
    : '';
  return `${range}${months}`;
}

function renderComment(comment) {
  const parent = comment.parentSeedKey ? commentByKey.get(comment.parentSeedKey) : null;
  return `<article class="comment${parent ? ' reply' : ''}" data-author="${escapeHtml(comment.authorSeedKey)}"><div class="comment-head"><strong>${escapeHtml(accountLabel(comment.authorSeedKey))}</strong><span class="target">《${escapeHtml(targetLabel(comment))}》</span>${parent ? `<span class="reply-to">回复 ${escapeHtml(accountLabel(parent.authorSeedKey))}</span>` : ''}<time class="time">${formatTime(comment.createdAt)}</time></div><p>${escapeHtml(comment.content)}</p></article>`;
}

function renderTargetStat(stat) {
  const target = targetFromKey(stat.target);
  const heat = stat.comments + stat.like + stat.fav;
  return `<div class="stat-row"><span>${escapeHtml(targetLabel(target))}</span><strong>线索 ${stat.roots} · 回复 ${stat.replies} · 赞 ${stat.like} · 藏 ${stat.fav} · 热度 ${heat}${stat.views == null ? '' : ` · 浏览 ≥ ${escapeHtml(stat.views)}`}</strong></div>`;
}

function renderZeroCommentTarget(stat) {
  return `<div class="stat-row"><span>${escapeHtml(targetLabel(targetFromKey(stat.target)))}</span><strong>赞 ${stat.like} · 藏 ${stat.fav}${stat.views == null ? '' : ` · 浏览 ≥ ${escapeHtml(stat.views)}`}</strong></div>`;
}

function renderFollow(follow) {
  const target = follow.toAccountSeedKey ? accountLabel(follow.toAccountSeedKey) : `@${follow.toHandle}`;
  return `<div class="follow"><span>${escapeHtml(accountLabel(follow.fromAccountSeedKey))}</span><span class="arrow">→</span><span>${escapeHtml(target)}</span></div>`;
}

function renderView(view) {
  return `<div class="stat-row"><span>${escapeHtml(targetLabel(view))}</span><strong>至少 ${escapeHtml(view.minimumCount)}</strong></div>`;
}

function targetFromKey(key) {
  return key.startsWith('seed:') ? { postSeedKey: key.slice(5) } : { postSlug: key.slice(5) };
}

function compareTargetStats(left, right) {
  const heatDifference = (right.comments + right.like + right.fav) - (left.comments + left.like + left.fav);
  return heatDifference || left.target.localeCompare(right.target);
}

function compareViews(left, right) {
  return right.minimumCount - left.minimumCount || targetKey(left).localeCompare(targetKey(right));
}

function formatTime(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function formatMonth(value) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
  }).formatToParts(value);
  const year = parts.find((part) => part.type === 'year').value;
  const month = parts.find((part) => part.type === 'month').value;
  return `${year}-${month}`;
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>'"]/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    "'": '&#39;',
    '"': '&quot;',
  })[character]);
}

async function assertIgnoredProjectOutput(file) {
  const relative = path.relative(repoRoot, file);
  if (!relative || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error(`review output must stay inside the repository: ${file}`);
  }
  const outputDirectory = path.dirname(file);
  const directoryRelative = path.relative(repoRoot, outputDirectory);
  if (!directoryRelative) {
    throw new Error('review output directory must be Git-ignored: repository root');
  }
  try {
    execFileSync('git', ['check-ignore', '--quiet', '--no-index', relative], {
      cwd: repoRoot,
      stdio: 'ignore',
    });
  } catch {
    throw new Error(`review output must be Git-ignored: ${relative}`);
  }
  try {
    execFileSync('git', ['check-ignore', '--quiet', '--no-index', directoryRelative], {
      cwd: repoRoot,
      stdio: 'ignore',
    });
  } catch {
    throw new Error(`review output directory must be Git-ignored: ${directoryRelative}`);
  }

  const existingOutput = await lstatIfExists(file);
  if (existingOutput?.isSymbolicLink()) {
    throw new Error(`review output must not be a symbolic link: ${relative}`);
  }
  await assertNearestExistingParentInsideRepository(outputDirectory);
}

async function assertNearestExistingParentInsideRepository(start) {
  let current = start;
  while (!(await lstatIfExists(current))) {
    const parent = path.dirname(current);
    if (parent === current) {
      throw new Error(`review output parent does not exist inside the repository: ${start}`);
    }
    current = parent;
  }
  await assertRealPathInsideRepository(current);
}

async function assertRealPathInsideRepository(candidate) {
  const [realRepoRoot, realCandidate] = await Promise.all([realpath(repoRoot), realpath(candidate)]);
  const relative = path.relative(realRepoRoot, realCandidate);
  if (relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    throw new Error(`review output resolves outside the repository: ${candidate}`);
  }
}

async function lstatIfExists(file) {
  try {
    return await lstat(file);
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}
