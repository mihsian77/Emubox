const { chromium } = require('playwright');
const fs = require('fs');
const OUT = process.env.OUT_DIR || '/home/user/Doubao/chats/38444562484718594/emuhub-cn/bench-data';
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/local/bin/chromium', args: ['--no-sandbox'] });
  const page = await browser.newPage({ userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36', viewport:{width:1400,height:3000} });
  fs.mkdirSync(OUT, {recursive:true});
  const out = { fetched_at: new Date().toISOString(), levels: {} };
  for (const lvl of [1,2,3]) {
    const url = 'https://www.antutu.com/ranking/soc?level='+lvl;
    await page.goto(url,{timeout:30000,waitUntil:'domcontentloaded'});
    await page.waitForTimeout(3500);
    // 分页检查：找页码元素/下一页
    const pg = await page.evaluate(()=>{
      const body=document.body.innerText||'';
      const hasNext=/下一页|尾页|加载更多/.test(body);
      const pages=[...document.querySelectorAll('a,li,span,button')].map(e=>e.innerText.trim()).filter(t=>/^\d{1,2}$/.test(t)).slice(-8);
      return {hasNext, pages};
    });
    const d = await page.evaluate(()=>{
      const b=document.body.innerText||'';
      const i=b.indexOf('Soc名称'); const j=b.indexOf('IoT芯片', i>=0?i:0);
      return {txt: j>0? b.slice(i,j): b.slice(i)};
    });
    out.levels['level'+lvl] = { url, page:1, hasNext: pg.hasNext, pageNums: pg.pages, txt: d.txt };
    console.log('level'+lvl, 'len', d.txt.length, 'hasNext', pg.hasNext, 'pageNums', JSON.stringify(pg.pages));
  }
  fs.writeFileSync(OUT+'/antutu_soc_raw.json', JSON.stringify(out,null,1));
  console.log('saved', OUT+'/antutu_soc_raw.json');
  await browser.close();
})().catch(e=>{console.error('ERR',e.message);process.exit(1);});
