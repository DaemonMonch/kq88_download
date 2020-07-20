//const pp = require("puppeteer-core");
const ppe = require("puppeteer-extra");
const axios = require("axios");
const process = require("process");
const { resolve } = require("path");

const downloadServer = "http://42.51.11.94:3004";
const xmlPattern = RegExp(".*?record[0-9]*\\.xml\\?t=.+")
ppe.use(require("puppeteer-extra-plugin-flash")({
  pluginPath:'C:\\Users\\dm\\AppData\\Local\\Google\\Chrome\\User Data\\PepperFlash\\32.0.0.387\\pepflashplayer.dll'
}));

const url = process.argv[2];

let wait = async (time) => {
  return new Promise(resolve => setTimeout(resolve,time));
}

(async () => {
  const browser = await ppe.launch({
    headless: false,
    executablePath: "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    args: [
      //  '--window-size=800,600',
      '--user-data-dir=E:\\tmp',
      "--enable-webgl",
      "--mute-audio",
      "--enable-accelerated-2d-canvas",
    ],
  });
  
  browser.on("disconnected", () => {
    console.log("browser disconnected");
  });
  
  const requestUrls = [];
  let a = false,b = false;
  let type = 0; //0-flash 1-html

  try {
    page = await browser.newPage();
    page.on("request", async (r) => {
      
      const url = r.url();

      if(url.endsWith('.xml')
      		|| xmlPattern.test(url)
      		|| url.endsWith('.grf')
      		|| url.endsWith('.ts')
      		|| url.endsWith('.m3u8')
      		|| url.indexOf('GenseeVod.swf') > -1){
       	console.log(url);
        if(url.indexOf('GenseeVod.swf') < 0)
          requestUrls.push(url)
        if(url.endsWith(".m3u8")){
          type = 1;
        }
      }
        
    });

    //console.log(await page.content());
    let name ;
    try{
    await page.goto(url, { timeout: 120000, waitUntil: ["domcontentloaded","networkidle0"] });
      //name = await page.$eval(".gs-live-in-fo",d => d.innerHTML)
      name = await page.title();
    }catch(e){};

    name = name || "unknown";
    console.log(`type ${type} name ${name}`);
    await wait(3000);
     let downUrl = type == 1 ? "/down" : "/flashdown" 
     resp = await axios.post(downloadServer  + downUrl, {
      name: name,
      data: requestUrls,
    });
    console.log(resp.data);
  } catch (e) {
    console.log(e);
  } finally {
    if (page) {
      await page.close();
      await browser.close();
    }
  }

})();
