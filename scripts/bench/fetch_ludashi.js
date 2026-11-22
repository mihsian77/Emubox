const { chromium } = require('playwright');
const fs = require('fs');
const OUT = process.env.OUT_DIR || require('path').resolve(__dirname, '../../bench-data');
const RANK_URL = 'https://www.ludashi.com/rank';

// 站点是纯国内服务，GitHub Actions 的海外出口经常卡在 domcontentloaded
// 一直等到 120s 超时（11-17 / 11-19 / 11-21 连续复现）。分两种等法：
//   第 1 次只等响应头（commit），拿到 HTML 后自己给渲染时间——跨境链路上
//   首字节很快，卡的是后续静态资源；
//   第 2 次退回完整的 domcontentloaded 兜底。
async function openRank(page) {
  try {
    await page.goto(RANK_URL, { timeout: 30000, waitUntil: 'commit' });
    await page.waitForTimeout(6000);
    return;
  } catch (e) {
    console.error(`[warn] 榜单页首字节等待失败（30s）：${e.message}，改用完整等待重试`);
    await page.waitForTimeout(5000);
  }
  await page.goto(RANK_URL, { timeout: 60000, waitUntil: 'domcontentloaded' });
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--no-sandbox'] });
  const page = await browser.newPage({ userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36', viewport:{width:1400,height:2000} });
  fs.mkdirSync(OUT,{recursive:true});
  await openRank(page);
  await page.waitForTimeout(3000);
  async function clickText(t){return await page.evaluate((tt)=>{const els=[...document.querySelectorAll('*')];for(const el of els){if(el.children.length===0&&el.innerText&&el.innerText.trim()===tt){el.click();return true;}}return false;},t);}
  async function grab(){
    return await page.evaluate(()=>{const b=document.body.innerText||'';const i=b.indexOf('更新日期');const hasMore=/加载更多|下一页|查看更多/.test(b);return {txt:b.slice(i>=0?i:0), hasMore};});
  }
  const out={fetched_at:new Date().toISOString()};
  await clickText('手机性能排行'); await page.waitForTimeout(2800); out.phone=await grab();
  await clickText('手机芯片排行'); await page.waitForTimeout(2800); out.soc=await grab();
  await clickText('处理器'); await page.waitForTimeout(2200); await clickText('台式机'); await page.waitForTimeout(2800); out.desktop_cpu=await grab();
  await clickText('显卡'); await page.waitForTimeout(2200); await clickText('台式机'); await page.waitForTimeout(2800); out.desktop_gpu=await grab();
  await clickText('处理器'); await page.waitForTimeout(2200); await clickText('笔记本'); await page.waitForTimeout(2800); out.laptop_cpu=await grab();
  fs.writeFileSync(OUT+'/ludashi_raw.json', JSON.stringify(out,null,1));
  for(const k of ['phone','soc','desktop_cpu','desktop_gpu','laptop_cpu']){
    console.log(k,'len',out[k].txt.length,'hasMore',out[k].hasMore);
  }
  await browser.close();
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});