import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { after, before, test } from 'node:test';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const scriptPath = path.join(scriptDir, 'render-community-interaction-review.mjs');
const testRoot = path.join(
  repoRoot,
  '.codex-tmp',
  'review-generator-tests',
  `${process.pid}-${Date.now()}`,
);
const packRoot = path.join(testRoot, 'pack');
const firstOutput = path.join(testRoot, 'first.html');
const secondOutput = path.join(testRoot, 'second.html');

before(async () => {
  await mkdir(packRoot, { recursive: true });
  await Promise.all([
    writeJson('accounts.yml', [
      {
        seedKey: 'account-a',
        handle: 'reader-a',
        nickname: '阿<script>alert(1)</script>',
        bio: '会看 <b>细节</b>',
        tags: ['动画', '<img>'],
        joinedAt: '2026-01-02T03:04:05+08:00',
      },
      {
        seedKey: 'account-b',
        handle: 'reader-b',
        nickname: '乙',
        bio: '技术读者',
        tags: ['Java'],
        joinedAt: '2026-02-03T04:05:06+08:00',
      },
    ]),
    writeJson('assets.yml', []),
    writeJson('posts.yml', [
      { seedKey: 'post-a', title: '有讨论的 <img src=x onerror=alert(1)> 文章' },
      { seedKey: 'post-b', title: '暂时没有评论的文章' },
    ]),
    writeJson('interactions.yml', {
      comments: [
        {
          seedKey: 'comment-root',
          authorSeedKey: 'account-a',
          postSeedKey: 'post-a',
          content: '三月的 <em>坏</em> 标签',
          createdAt: '2026-03-01T10:00:00+08:00',
        },
        {
          seedKey: 'comment-reply',
          parentSeedKey: 'comment-root',
          authorSeedKey: 'account-b',
          postSeedKey: 'post-a',
          content: '四月回复 & 继续聊',
          createdAt: '2026-04-02T11:00:00+08:00',
        },
        {
          seedKey: 'comment-owner-post',
          authorSeedKey: 'account-a',
          postSlug: 'owner-post',
          content: '五月翻到旧文',
          createdAt: '2026-05-03T12:00:00+08:00',
        },
      ],
      reactions: [
        { accountSeedKey: 'account-a', postSeedKey: 'post-a', type: 'like' },
        { accountSeedKey: 'account-b', postSeedKey: 'post-b', type: 'fav' },
        { accountSeedKey: 'account-b', postSlug: 'owner-post', type: 'like' },
      ],
      follows: [
        { fromAccountSeedKey: 'account-a', toAccountSeedKey: 'account-b' },
        { fromAccountSeedKey: 'account-b', toHandle: 'Rekyrice' },
      ],
      views: [
        { postSeedKey: 'post-a', minimumCount: 10 },
        { postSeedKey: 'post-b', minimumCount: '<img src=x onerror=alert(2)>' },
        { postSlug: 'owner-post', minimumCount: 25 },
      ],
    }),
  ]);
});

after(async () => {
  await rm(testRoot, { recursive: true, force: true });
});

test('escapes pack text and renders complete interaction statistics', async () => {
  runGenerator(firstOutput);
  const html = await readFile(firstOutput, 'utf8');

  assert.doesNotMatch(html, /阿<script>alert\(1\)<\/script>/);
  assert.doesNotMatch(html, /三月的 <em>坏<\/em> 标签/);
  assert.doesNotMatch(html, /<img src=x onerror=alert\(1\)>/);
  assert.doesNotMatch(html, /<img src=x onerror=alert\(2\)>/);
  assert.match(html, /阿&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(html, /三月的 &lt;em&gt;坏&lt;\/em&gt; 标签/);
  assert.match(html, /&lt;img src=x onerror=alert\(2\)&gt;/);

  assert.match(html, /总评论[\s\S]*3/);
  assert.match(html, /主评论[\s\S]*2/);
  assert.match(html, /回复[\s\S]*1/);
  assert.match(html, /评论覆盖[\s\S]*2 \/ 3/);
  assert.match(html, /账号参与分布/);
  assert.match(html, /评 2 · 赞 1 · 藏 0 · 关注 1 \/ 0/);
  assert.match(html, /评 1 · 赞 1 · 藏 1 · 关注 1 \/ 1/);
  assert.match(html, /零评论目标（1）/);
  assert.match(html, /暂时没有评论的文章/);
  assert.match(html, /评论时间分布/);
  assert.match(html, /2026-03[\s\S]*1 条/);
  assert.match(html, /2026-04[\s\S]*1 条/);
  assert.match(html, /2026-05[\s\S]*1 条/);
  assert.match(html, /@Rekyrice/);
});

test('writes deterministic output for the same pack', async () => {
  runGenerator(firstOutput);
  runGenerator(secondOutput);
  assert.equal(await readFile(firstOutput, 'utf8'), await readFile(secondOutput, 'utf8'));
});

test('rejects an output path that is not Git-ignored', () => {
  const unsafeOutput = path.join(scriptDir, `.review-generator-should-not-exist-${process.pid}.html`);
  let failure;
  try {
    runGenerator(unsafeOutput);
  } catch (error) {
    failure = error;
  }
  assert.ok(failure, 'generator should reject a tracked-directory output path');
  assert.match(String(failure.stderr), /review output must be Git-ignored/);
});

test('rejects an individually ignored file in a tracked directory', async () => {
  const unsafeOutput = path.join(scriptDir, `.env.review-${process.pid}.local`);
  let failure;
  try {
    runGenerator(unsafeOutput);
  } catch (error) {
    failure = error;
  } finally {
    await rm(unsafeOutput, { force: true });
  }
  assert.ok(failure, 'generator should require an ignored output directory, not only an ignored file');
  assert.match(String(failure.stderr), /review output directory must be Git-ignored/);
});

function runGenerator(outputFile) {
  return execFileSync(process.execPath, [scriptPath, packRoot, outputFile], {
    cwd: repoRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

async function writeJson(fileName, value) {
  await writeFile(path.join(packRoot, fileName), `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}
