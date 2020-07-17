const koa = require("koa");
const Router = require("koa-router");
const stc = require("koa-static");
const bodyParser = require("koa-bodyparser");
//const pp = require("puppeteer-core");
const ppe = require('puppeteer-extra');
const views = require("koa-views");
const axios = require("axios");

const app = new koa();
app.use(async (ctx,next) => {ctx.request.url = ctx.request.url.replace("//","/");await next()});
app.use(views("./public", { map: { html: "mustache" } }));
app.use(bodyParser());
app.use(stc("public"));
const router = new Router();

ppe.use(require('puppeteer-extra-plugin-flash')());

launch = () => ppe.launch({
	headless:false,
  executablePath:
    "google-chrome",
	args: [
         //  '--window-size=800,600',
             '--enable-webgl',
          '--enable-accelerated-2d-canvas',
        ],	   
});

let bp = launch();
bp.then(b => b.on('disconnected', () => {
    console.log('browser disconnected');
    bp = launch();
}));
router.get("/", async (ctx, next) => {
  await ctx.render("1", { urls: [] });
});

router.post("/", async (ctx, next) => {
  const url = ctx.request.body.url;
  const browser = await bp;
  let err,page;
  let requestUrls = [];
  let count = 0;
  let a=false,b=false;
  let resp ;
  try{
    page = await browser.newPage();
    page.on("request", async r => {
		console.log(r.url());
		if(a && b)
			return;
		if(r.url().endsWith('.ts')){
			a = true;
			//console.log(r.url());
			requestUrls.push(r.url());
		}
		if(r.url().endsWith('.m3u8')){
			b = true
			//console.log(r.url());
			requestUrls.push(r.url());
		}
		
    });
    await page.goto(url,{timeout: 120000,waitUntil:'networkidle0'});
//console.log(await page.content());
    let name = await page.$eval(".gs-live-in-fo",d => d.innerHTML);
	console.log(name)
	resp = await axios.post('http://localhost:3004/down',{name:name,data:requestUrls})
  }catch(e){
    err = e;  
    console.error(e);
  }finally{
      if(page){
        await page.close();
      }
	  
    await ctx.render("1", { urls:resp != undefined ? resp.data : [], url: url,err:err,count:count });
  
  }

  //let m = await page.metrics();
});

app.use(router.routes()).use(router.allowedMethods());
//app.listen(3003,'42.51.11.94');
app.listen(3003);
