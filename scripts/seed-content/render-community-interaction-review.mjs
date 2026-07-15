import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
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

const [accounts, posts, interactions] = await Promise.all([
  loadYaml('accounts.yml'),
  loadYaml('posts.yml'),
  loadYaml('interactions.yml'),
]);

const accountByKey = new Map(accounts.map((account) => [account.seedKey, account]));
const postByKey = new Map(posts.map((post) => [post.seedKey, post]));
const commentByKey = new Map(interactions.comments.map((comment) => [comment.seedKey, comment]));
const reactionStats = aggregateReactions(interactions.reactions);
const roots = interactions.comments.filter((comment) => !comment.parentSeedKey).length;
const replies = interactions.comments.length - roots;
const likes = interactions.reactions.filter((reaction) => reaction.type === 'like').length;
const favorites = interactions.reactions.filter((reaction) => reaction.type === 'fav').length;

const html = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Chtholly Hub 社区互动包审阅</title>
  <style>
    :root { color-scheme: light; --ink:#25314a; --muted:#6e7890; --line:#dfe5ef; --paper:#fbfcff; --blue:#4c83b9; --rose:#bc7289; }
    * { box-sizing: border-box; }
    body { margin:0; color:var(--ink); font:15px/1.75 "Segoe UI","Microsoft YaHei",sans-serif; background:linear-gradient(135deg,#f5f8fd,#fff8f4 55%,#f6f1fb); }
    main { width:min(1180px,calc(100% - 32px)); margin:32px auto 80px; }
    header,.panel { background:rgba(255,255,255,.88); border:1px solid rgba(212,220,234,.9); border-radius:22px; box-shadow:0 18px 55px rgba(76,92,126,.10); }
    header { padding:34px 38px; }
    h1,h2,h3,p { margin-top:0; }
    h1 { margin-bottom:8px; font-size:30px; letter-spacing:.02em; }
    h2 { margin-bottom:18px; font-size:22px; }
    h3 { margin-bottom:6px; font-size:16px; }
    .muted { color:var(--muted); }
    .summary { display:grid; grid-template-columns:repeat(6,1fr); gap:12px; margin-top:26px; }
    .metric { padding:14px 12px; border-radius:15px; background:#f5f8fc; text-align:center; }
    .metric strong { display:block; font-size:24px; color:var(--blue); }
    .panel { margin-top:22px; padding:28px 30px; }
    .profiles { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; }
    .profile,.comment,.stat-row,.follow { border:1px solid var(--line); border-radius:16px; background:var(--paper); }
    .profile { padding:16px; }
    .handle,.time,.target { color:var(--muted); font-size:13px; }
    .tags { display:flex; flex-wrap:wrap; gap:6px; margin-top:10px; }
    .tag { padding:2px 9px; border-radius:999px; background:#eaf2fa; color:#426d96; font-size:12px; }
    .toolbar { display:flex; gap:12px; align-items:center; margin-bottom:16px; }
    select { border:1px solid var(--line); border-radius:10px; padding:8px 12px; background:white; color:var(--ink); }
    .comments { display:grid; gap:12px; }
    .comment { padding:17px 19px; }
    .comment.reply { margin-left:46px; border-left:4px solid #e3b4c2; }
    .comment-head { display:flex; flex-wrap:wrap; gap:8px 14px; align-items:baseline; margin-bottom:8px; }
    .comment p { margin:0; font-size:15px; }
    .reply-to { color:var(--rose); }
    .stats { display:grid; grid-template-columns:1.15fr .85fr; gap:20px; }
    .stat-list,.follow-list { display:grid; gap:8px; }
    .stat-row,.follow { padding:10px 13px; display:flex; justify-content:space-between; gap:14px; }
    .follow { justify-content:flex-start; }
    .arrow { color:var(--rose); font-weight:700; }
    @media (max-width:900px) { .summary { grid-template-columns:repeat(3,1fr); } .profiles { grid-template-columns:repeat(2,1fr); } .stats { grid-template-columns:1fr; } }
    @media (max-width:560px) { main { width:min(100% - 18px,1180px); margin-top:10px; } header,.panel { padding:20px 17px; border-radius:16px; } .profiles { grid-template-columns:1fr; } .comment.reply { margin-left:18px; } }
  </style>
</head>
<body>
<main>
  <header>
    <h1>社区互动包 · 导入前审阅</h1>
    <p class="muted">本页只读取 content-v3 文件，不连接数据库，也不会产生任何站内行为。评论逐条展示，其余互动按目标统计。</p>
    <div class="summary">
      ${metric('账号', accounts.length)}${metric('主评论', roots)}${metric('回复', replies)}
      ${metric('点赞', likes)}${metric('收藏', favorites)}${metric('关注 / 浏览', `${interactions.follows.length} / ${interactions.views.length}`)}
    </div>
  </header>

  <section class="panel">
    <h2>账号资料</h2>
    <div class="profiles">${accounts.map(renderProfile).join('')}</div>
  </section>

  <section class="panel">
    <h2>评论逐条审阅</h2>
    <div class="toolbar"><label for="author-filter">按账号筛选</label><select id="author-filter"><option value="">全部账号</option>${accounts.map((account) => `<option value="${escapeHtml(account.seedKey)}">${escapeHtml(account.nickname)}</option>`).join('')}</select><span id="visible-count" class="muted"></span></div>
    <div class="comments">${interactions.comments.map(renderComment).join('')}</div>
  </section>

  <section class="panel">
    <h2>其余互动统计</h2>
    <div class="stats">
      <div><h3>点赞与收藏分布</h3><div class="stat-list">${[...reactionStats.values()].sort((a,b) => (b.like+b.fav)-(a.like+a.fav)).map(renderReactionStat).join('')}</div></div>
      <div><h3>关注关系</h3><div class="follow-list">${interactions.follows.map(renderFollow).join('')}</div></div>
      <div><h3>浏览基线（内容包文章）</h3><div class="stat-list">${interactions.views.filter((view) => view.postSeedKey).sort((a,b) => b.minimumCount-a.minimumCount).map(renderView).join('')}</div></div>
      <div><h3>浏览基线（Rekyrice 已有文章）</h3><div class="stat-list">${interactions.views.filter((view) => view.postSlug).sort((a,b) => b.minimumCount-a.minimumCount).map(renderView).join('')}</div></div>
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
await writeFile(outputFile, html, 'utf8');
console.log(`review=${outputFile}`);
console.log(`accounts=${accounts.length} comments=${interactions.comments.length} likes=${likes} favorites=${favorites} follows=${interactions.follows.length} views=${interactions.views.length}`);

async function loadYaml(file) {
  return YAML.parse(await readFile(path.join(packRoot, file), 'utf8'));
}

function aggregateReactions(reactions) {
  const stats = new Map();
  for (const reaction of reactions) {
    const target = targetKey(reaction);
    const current = stats.get(target) ?? { target, like: 0, fav: 0 };
    current[reaction.type] += 1;
    stats.set(target, current);
  }
  return stats;
}

function targetKey(value) { return value.postSeedKey ? `seed:${value.postSeedKey}` : `slug:${value.postSlug}`; }
function targetLabel(value) { return value.postSeedKey ? (postByKey.get(value.postSeedKey)?.title ?? value.postSeedKey) : `Rekyrice / ${value.postSlug}`; }
function accountLabel(key) { const account = accountByKey.get(key); return account ? `${account.nickname} (@${account.handle})` : key; }
function metric(label, value) { return `<div class="metric"><strong>${escapeHtml(value)}</strong>${escapeHtml(label)}</div>`; }
function renderProfile(account) { return `<article class="profile"><h3>${escapeHtml(account.nickname)}</h3><div class="handle">@${escapeHtml(account.handle)} · 加入于 ${formatTime(account.joinedAt)}</div><p>${escapeHtml(account.bio)}</p><div class="tags">${account.tags.map((tag) => `<span class="tag">${escapeHtml(tag)}</span>`).join('')}</div></article>`; }
function renderComment(comment) { const parent = comment.parentSeedKey ? commentByKey.get(comment.parentSeedKey) : null; return `<article class="comment${parent ? ' reply' : ''}" data-author="${escapeHtml(comment.authorSeedKey)}"><div class="comment-head"><strong>${escapeHtml(accountLabel(comment.authorSeedKey))}</strong><span class="target">《${escapeHtml(targetLabel(comment))}》</span>${parent ? `<span class="reply-to">回复 ${escapeHtml(accountLabel(parent.authorSeedKey))}</span>` : ''}<time class="time">${formatTime(comment.createdAt)}</time></div><p>${escapeHtml(comment.content)}</p></article>`; }
function renderReactionStat(stat) { const value = stat.target.startsWith('seed:') ? { postSeedKey: stat.target.slice(5) } : { postSlug: stat.target.slice(5) }; return `<div class="stat-row"><span>${escapeHtml(targetLabel(value))}</span><strong>赞 ${stat.like} · 收藏 ${stat.fav}</strong></div>`; }
function renderFollow(follow) { return `<div class="follow"><span>${escapeHtml(accountLabel(follow.fromAccountSeedKey))}</span><span class="arrow">→</span><span>${escapeHtml(accountLabel(follow.toAccountSeedKey))}</span></div>`; }
function renderView(view) { return `<div class="stat-row"><span>${escapeHtml(targetLabel(view))}</span><strong>至少 ${view.minimumCount}</strong></div>`; }
function formatTime(value) { return new Intl.DateTimeFormat('zh-CN', { timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' }).format(new Date(value)); }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>'"]/g, (character) => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;' })[character]); }
