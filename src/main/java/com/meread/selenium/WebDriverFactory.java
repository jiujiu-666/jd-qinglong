package com.meread.selenium;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meread.selenium.bean.*;
import com.meread.selenium.util.CacheUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.html5.LocalStorage;
import org.openqa.selenium.remote.AbstractDriverOptions;
import org.openqa.selenium.remote.RemoteExecuteMethod;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.html5.RemoteWebStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author yangxg
 * @date 2021/9/7
 */
@Component
@Slf4j
public class WebDriverFactory implements CommandLineRunner {

    @Autowired
    ResourceLoader resourceLoader;

    @Autowired
    private CacheUtil cacheUtil;

    @Autowired
    private JDService jdService;

    @Value("${selenium.hub.url}")
    private String seleniumHubUrl;

    @Value("${env.path}")
    private String envPath;

    @Value("${selenium.hub.status.url}")
    private String seleniumHubStatusUrl;

    @Value("${op.timeout}")
    private int opTimeout;

    @Value("${selenium.type}")
    private String seleniumType;

    public static final String CLIENT_SESSION_ID_KEY = "client:session";

    private List<QLConfig> qlConfigs;

    public Properties properties = new Properties();

    private String xddUrl;

    private String xddToken;

    private static int capacity = 0;

    public volatile boolean stopSchedule = false;
    public volatile boolean initSuccess = false;
    public volatile boolean runningSchedule = false;

    private List<MyChrome> chromes;

    public List<MyChrome> getChromes() {
        return chromes;
    }

    public static final ChromeOptions chromeOptions;
    public static final FirefoxOptions firefoxOptions;

    static {
        chromeOptions = new ChromeOptions();
        chromeOptions.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});
        chromeOptions.setExperimentalOption("useAutomationExtension", true);
        chromeOptions.addArguments("lang=zh-CN,zh,zh-TW,en-US,en");
        chromeOptions.addArguments("user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/67.0.3396.99 Safari/537.36");
        chromeOptions.addArguments("disable-blink-features=AutomationControlled");
        chromeOptions.addArguments("--disable-gpu");
        chromeOptions.addArguments("--headless");
//        chromeOptions.addArguments("--no-sandbox");
//        chromeOptions.addArguments("--disable-extensions");
//        chromeOptions.addArguments("--disable-software-rasterizer");
        chromeOptions.addArguments("--ignore-ssl-errors=yes");
        chromeOptions.addArguments("--ignore-certificate-errors");
//        chromeOptions.addArguments("--allow-running-insecure-content");
        chromeOptions.addArguments("--window-size=500,700");

        firefoxOptions = new FirefoxOptions();
        firefoxOptions.setHeadless(true);
//        firefoxOptions.addArguments("--headless");
//        firefoxOptions.addArguments("--disable-gpu");
        firefoxOptions.setAcceptInsecureCerts(true);
//        firefoxOptions.addArguments("--window-size=500,700");
    }

    public AbstractDriverOptions getOptions() {
        return seleniumType.equals("chrome") ? chromeOptions : firefoxOptions;
    }

    @Scheduled(initialDelay = 180000, fixedDelay = 60000)
    public void syncCK_count() {
        if (qlConfigs != null) {
            for (QLConfig qlConfig : qlConfigs) {
                int oldSize = qlConfig.getRemain();
                jdService.fetchCurrentCKS_count(qlConfig, "");
                int newSize = qlConfig.getRemain();
                log.info(qlConfig.getQlUrl() + " 容量从 " + oldSize + "变为" + newSize);
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
//    @Scheduled(initialDelay = 30000, fixedDelay = 30000)
    public void refreshOpenIdToken() {
        if (qlConfigs != null) {
            for (QLConfig qlConfig : qlConfigs) {
                if (qlConfig.getQlLoginType() == QLConfig.QLLoginType.TOKEN) {
                    QLToken qlTokenOld = qlConfig.getQlToken();
                    jdService.fetchNewOpenIdToken(qlConfig);
                    log.info(qlConfig.getQlToken() + " token 从" + qlTokenOld + " 变为 " + qlConfig.getQlToken());
                }
            }
        }
    }

    @Scheduled(initialDelay = 10000, fixedDelay = 2000)
    public void heartbeat() {
        runningSchedule = true;
        if (!stopSchedule) {
            List<NodeStatus> nss = getGridStatus();
            Iterator<MyChrome> iterator = chromes.iterator();
            while (iterator.hasNext()) {
                MyChrome myChrome = iterator.next();
                SessionId s = myChrome.getWebDriver().getSessionId();
                if (s == null) {
                    iterator.remove();
                    log.warn("quit a chrome");
                    continue;
                }
                String sessionId = s.toString();
                boolean find = false;
                for (NodeStatus ns : nss) {
                    List<SlotStatus> slotStatus = ns.getSlotStatus();
                    for (SlotStatus ss : slotStatus) {
                        if (sessionId.equals(ss.getSessionId())) {
                            find = true;
                            break;
                        }
                    }
                    if (find) {
                        break;
                    }
                }
                //如果session不存在，则remove
                if (!find) {
                    iterator.remove();
                    log.warn("quit a chrome");
                }
            }
            int shouldCreate = capacity - chromes.size();
            if (shouldCreate > 0) {
                int currCount = 0;
                do {
                    try {
                        RemoteWebDriver webDriver = new RemoteWebDriver(new URL(seleniumHubUrl), getOptions());
                        MyChrome myChrome = new MyChrome();
                        myChrome.setWebDriver(webDriver);
                        log.warn("create a chrome " + webDriver.getSessionId().toString());
                        chromes.add(myChrome);
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                    currCount++;
                } while (currCount < shouldCreate / 2);
            }
            inflate(chromes, getGridStatus());
        }
        runningSchedule = false;
    }

    @Autowired
    private RestTemplate restTemplate;

    public List<NodeStatus> getGridStatus() {
        String url = seleniumHubStatusUrl;
        String json = restTemplate.getForObject(url, String.class);
        JSONObject value = JSON.parseObject(json).getJSONObject("value");
        Boolean ready = value.getBoolean("ready");
        List<NodeStatus> res = new ArrayList<>();
        if (ready) {
            JSONArray nodes = value.getJSONArray("nodes");
            for (int i = 0; i < nodes.size(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                NodeStatus status = new NodeStatus();

                String uri = node.getString("uri");
                String nodeStatusUrl = String.format("%s/status", uri);
                String nodeStatusJson = restTemplate.getForObject(nodeStatusUrl, String.class);
                boolean nodeReady = JSON.parseObject(nodeStatusJson).getJSONObject("value").getBooleanValue("ready");
                status.setFullSession(!nodeReady);
                status.setMaxSessions(node.getInteger("maxSessions"));

                String availability = node.getString("availability");
                status.setAvailability(availability);
                if ("UP".equals(availability)) {
                    JSONArray slots = node.getJSONArray("slots");
                    List<SlotStatus> sss = new ArrayList<>();
                    for (int s = 0; s < slots.size(); s++) {
                        JSONObject currSession = slots.getJSONObject(s).getJSONObject("session");
                        SlotStatus ss = new SlotStatus();
                        Date start = null;
                        String sessionId = null;
                        if (currSession != null) {
                            String locald = currSession.getString("start");
                            locald = locald.substring(0, locald.lastIndexOf(".")) + " UTC";
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss Z");
                            try {
                                start = sdf.parse(locald);
                            } catch (ParseException e) {
                                e.printStackTrace();
                            }
                            sessionId = currSession.getString("sessionId");
                        }
                        ss.setSessionStartTime(start);
                        ss.setSessionId(sessionId);
                        ss.setBelongsToUri(node.getString("uri"));
                        sss.add(ss);
                    }
                    status.setSlotStatus(sss);
                    status.setUri(uri);
                    status.setNodeId(node.getString("id"));
                }

                res.add(status);
            }
        }
        return res;
    }

    public void closeSession(String uri, String sessionId) {
        String deleteUrl = String.format("%s/session/%s", uri, sessionId);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-REGISTRATION-SECRET", "");
        HttpEntity<?> request = new HttpEntity<>(headers);
        ResponseEntity<String> exchange = null;
        try {
            exchange = restTemplate.exchange(deleteUrl, HttpMethod.DELETE, request, String.class);
            log.info("close sessionId : " + sessionId + " resp : " + exchange.getBody() + ", resp code : " + exchange.getStatusCode());
        } catch (RestClientException e) {
            e.printStackTrace();
        }
    }

    private void inflate(List<MyChrome> chromes, List<NodeStatus> statusList) {
        for (MyChrome chrome : chromes) {
            for (NodeStatus status : statusList) {
                String currSessionId = chrome.getWebDriver().getSessionId().toString();
                List<SlotStatus> slotStatus = status.getSlotStatus();
                boolean find = false;
                for (SlotStatus ss : slotStatus) {
                    if (currSessionId.equals(ss.getSessionId())) {
                        chrome.setSlotStatus(ss);
                        find = true;
                        break;
                    }
                }
                if (find) {
                    break;
                }
            }
        }
    }

    @Override
    public void run(String... args) throws MalformedURLException {
        stopSchedule = true;
        chromes = Collections.synchronizedList(new ArrayList<>());

        log.info("解析配置不初始化");
        parseMultiQLConfig();

        //获取hub-node状态
        List<NodeStatus> statusList = getNodeStatuses();
        if (statusList == null) {
            throw new RuntimeException("Selenium 浏览器组初始化失败");
        }

        //清理未关闭的session
        log.info("清理未关闭的session，获取最大容量");
        for (NodeStatus status : statusList) {
            List<SlotStatus> sss = status.getSlotStatus();
            if (sss != null) {
                for (SlotStatus ss : sss) {
                    if (ss != null && ss.getSessionId() != null) {
                        try {
                            closeSession(status.getUri(), ss.getSessionId());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            capacity += status.getMaxSessions();
        }

        if (capacity <= 0) {
            log.error("capacity <= 0");
            throw new RuntimeException("无法创建浏览器实例");
        }

        //初始化一半Chrome实例
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (int i = 0; i < (capacity == 1 ? 2 : capacity) / 2; i++) {
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    log.info("初始化Chrome实例");
                    RemoteWebDriver webDriver = null;
                    try {
                        webDriver = new RemoteWebDriver(new URL(seleniumHubUrl), getOptions());
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                    webDriver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
                    MyChrome myChrome = new MyChrome();
                    myChrome.setWebDriver(webDriver);
                    chromes.add(myChrome);
                }
            });
        }

        executorService.shutdown();

        inflate(chromes, getGridStatus());
        //借助这一半chrome实例，初始化配置
        initConfig();
        if (qlConfigs.isEmpty()) {
            log.warn("请配置至少一个青龙面板地址! 否则获取到的ck无法上传");
        }

        log.info("启动成功!");
        stopSchedule = false;
        initSuccess = true;
    }

    private List<NodeStatus> getNodeStatuses() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<List<NodeStatus>> startSeleniumRes = CompletableFuture.supplyAsync(() -> {
            while (true) {
                List<NodeStatus> statusList = getGridStatus();
                if (statusList.size() > 0) {
                    return statusList;
                }
            }
        }, executor);
        List<NodeStatus> statusList = null;
        try {
            statusList = startSeleniumRes.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
        executor.shutdown();
        return statusList;
    }

    private void parseMultiQLConfig() {
        qlConfigs = new ArrayList<>();
        if (envPath.startsWith("classpath")) {
            Resource resource = resourceLoader.getResource(envPath);
            try (InputStreamReader inputStreamReader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                properties.load(inputStreamReader);
            } catch (IOException e) {
                throw new RuntimeException("env.properties配置有误");
            }
        } else {
            File envFile = new File(envPath);
            if (!envFile.exists()) {
                throw new RuntimeException("env.properties配置有误");
            }
            try (BufferedReader br = new BufferedReader(new FileReader(envFile, StandardCharsets.UTF_8))) {
                properties.load(br);
            } catch (IOException e) {
                throw new RuntimeException("env.properties配置有误");
            }
        }

        xddUrl = properties.getProperty("XDD_URL");
        xddToken = properties.getProperty("XDD_TOKEN");

        for (int i = 1; i <= 5; i++) {
            QLConfig config = new QLConfig();
            config.setId(i);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                if ("SPRING_PROFILES_ACTIVE".equals(key)) {
                    jdService.setDebug("debug".equals(value));
                }
                if (key.equals("QL_USERNAME_" + i)) {
                    config.setQlUsername(value);
                } else if (key.equals("QL_URL_" + i)) {
                    if (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    config.setQlUrl(value);
                } else if (key.equals("QL_PASSWORD_" + i)) {
                    config.setQlPassword(value);
                } else if (key.equals("QL_CLIENTID_" + i)) {
                    config.setQlClientID(value);
                } else if (key.equals("QL_SECRET_" + i)) {
                    config.setQlClientSecret(value);
                } else if (key.equals("QL_LABEL_" + i)) {
                    config.setLabel(value);
                } else if (key.equals("QL_CAPACITY_" + i)) {
                    config.setCapacity(Integer.parseInt(value));
                }
            }
            if (config.isValid()) {
                qlConfigs.add(config);
            }
        }
        log.info("解析" + qlConfigs.size() + "套配置");
    }

    private void initConfig() {
        Iterator<QLConfig> iterator = qlConfigs.iterator();
        while (iterator.hasNext()) {
            QLConfig qlConfig = iterator.next();
            if (StringUtils.isEmpty(qlConfig.getLabel())) {
                qlConfig.setLabel("请配置QL_LABEL_" + qlConfig.getId() + "");
            }

            boolean verify1 = !StringUtils.isEmpty(qlConfig.getQlUrl());
            boolean verify2 = verify1 && !StringUtils.isEmpty(qlConfig.getQlUsername()) && !StringUtils.isEmpty(qlConfig.getQlPassword());
            boolean verify3 = verify1 && !StringUtils.isEmpty(qlConfig.getQlClientID()) && !StringUtils.isEmpty(qlConfig.getQlClientSecret());

            boolean result_token = false;
            boolean result_usernamepassword = false;
            if (verify3) {
                boolean success = getToken(qlConfig);
                if (success) {
                    result_token = true;
                    qlConfig.setQlLoginType(QLConfig.QLLoginType.TOKEN);
                    jdService.fetchCurrentCKS_count(qlConfig, "");
                } else {
                    log.warn(qlConfig.getQlUrl() + "获取token失败，获取到的ck无法上传，已忽略");
                }
            }
            if (verify2) {
                boolean result = false;
                try {
                    result = initInnerQingLong(qlConfig);
                    qlConfig.setQlLoginType(QLConfig.QLLoginType.USERNAME_PASSWORD);
                    jdService.fetchCurrentCKS_count(qlConfig, "");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (result) {
                    result_usernamepassword = true;
                } else {
                    log.info("初始化青龙面板" + qlConfig.getQlUrl() + "登录失败, 获取到的ck无法上传，已忽略");
                }
            }

            if (!result_token && !result_usernamepassword) {
                iterator.remove();
            }
        }

        log.info("成功添加" + qlConfigs.size() + "套配置");
    }

    public boolean getToken(QLConfig qlConfig) {
        String qlUrl = qlConfig.getQlUrl();
        String qlClientID = qlConfig.getQlClientID();
        String qlClientSecret = qlConfig.getQlClientSecret();
        try {
            ResponseEntity<String> entity = restTemplate.getForEntity(qlUrl + "/open/auth/token?client_id=" + qlClientID + "&client_secret=" + qlClientSecret, String.class);
            if (entity.getStatusCodeValue() == 200) {
                String body = entity.getBody();
                log.info("获取token " + body);
                JSONObject jsonObject = JSON.parseObject(body);
                Integer code = jsonObject.getInteger("code");
                if (code == 200) {
                    JSONObject data = jsonObject.getJSONObject("data");
                    String token = data.getString("token");
                    String tokenType = data.getString("token_type");
                    long expiration = data.getLong("expiration");
                    log.info(qlUrl + "获取token成功 " + token);
                    log.info(qlUrl + "获取tokenType成功 " + tokenType);
                    log.info(qlUrl + "获取expiration成功 " + expiration);
                    qlConfig.setQlToken(new QLToken(token, tokenType, expiration));
                    return true;
                }
            }
        } catch (Exception e) {
            log.error(qlUrl + "获取token失败，请检查配置");
        }
        return false;
    }

    public boolean initInnerQingLong(QLConfig qlConfig) throws MalformedURLException {
        String qlUrl = qlConfig.getQlUrl();
        String sessionId = assignSessionId(null, true, null).getAssignSessionId();
        MyChrome chrome = null;
        if (sessionId != null) {
            chrome = getMyChromeBySessionId(sessionId);
        }
        if (chrome == null) {
            throw new RuntimeException("请检查资源配置，资源数太少");
        }
        RemoteWebDriver webDriver = chrome.getWebDriver();
        webDriver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        try {
            String token = null;
            int retry = 0;
            while (StringUtils.isEmpty(token)) {
                retry++;
                if (retry > 2) {
                    break;
                }
                String qlUsername = qlConfig.getQlUsername();
                String qlPassword = qlConfig.getQlPassword();
                webDriver.get(qlUrl + "/login");
                log.info("initQingLong start : " + qlUrl + "/login");
                boolean b = WebDriverUtil.waitForJStoLoad(webDriver);
                if (b) {
                    webDriver.findElement(By.id("username")).sendKeys(qlUsername);
                    webDriver.findElement(By.id("password")).sendKeys(qlPassword);
                    webDriver.findElement(By.xpath("//button[@type='submit']")).click();
                    Thread.sleep(2000);
                    b = WebDriverUtil.waitForJStoLoad(webDriver);
                    if (b) {
                        RemoteExecuteMethod executeMethod = new RemoteExecuteMethod(webDriver);
                        RemoteWebStorage webStorage = new RemoteWebStorage(executeMethod);
                        LocalStorage storage = webStorage.getLocalStorage();
                        token = storage.getItem("token");
                        log.info("qinglong token " + token);
                        qlConfig.setQlToken(new QLToken(token));
                        readPassword(qlConfig);
                    }
                }
            }
        } catch (Exception e) {
            log.error(qlUrl + "测试登录失败，请检查配置");
        } finally {
            releaseWebDriver(sessionId);
        }
        return qlConfig.getQlToken() != null && qlConfig.getQlToken().getToken() != null;
    }

    private boolean readPassword(QLConfig qlConfig) throws IOException {
        File file = new File("/data/config/auth.json");
        if (file.exists()) {
            String s = FileUtils.readFileToString(file, "utf-8");
            JSONObject jsonObject = JSON.parseObject(s);
            String username = jsonObject.getString("username");
            String password = jsonObject.getString("password");
            if (!StringUtils.isEmpty(username) && !StringUtils.isEmpty(password) && !"adminadmin".equals(password)) {
                qlConfig.setQlUsername(username);
                qlConfig.setQlPassword(password);
                log.debug("username = " + username + ", password = " + password);
                return true;
            }
        }
        return false;
    }

    public RemoteWebDriver getDriverBySessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (MyChrome myChrome : chromes) {
            if (myChrome.getWebDriver().getSessionId().toString().equals(sessionId)) {
                return myChrome.getWebDriver();
            }
        }
        return null;
    }

    public MyChrome getMyChromeBySessionId(String sessionId) {
        if (chromes != null && chromes.size() > 0) {
            for (MyChrome myChrome : chromes) {
                if (myChrome.getWebDriver().getSessionId().toString().equals(sessionId)) {
                    return myChrome;
                }
            }
        }
        return null;
    }

    public synchronized AssignSessionIdStatus assignSessionId(String clientSessionId, boolean create, String servletSessionId) {
        AssignSessionIdStatus status = new AssignSessionIdStatus();

        if (clientSessionId == null && servletSessionId != null) {
//            String s = redisTemplate.opsForValue().get("servlet:session:" + servletSessionId);
            String s = cacheUtil.get("servlet:session:" + servletSessionId);
            if (!StringUtils.isEmpty(s)) {
                clientSessionId = s;
            }
        }

        if (clientSessionId != null) {
            status.setClientSessionId(clientSessionId);
            MyChrome myChrome = getMyChromeBySessionId(clientSessionId);
            if (myChrome != null && myChrome.getClientSessionId() != null) {
                status.setNew(false);
                status.setAssignSessionId(myChrome.getClientSessionId());
//                Long expire = redisTemplate.getExpire();
                Long expire = cacheUtil.getExpire(CLIENT_SESSION_ID_KEY + ":" + clientSessionId);
                if (expire != null && expire < 0) {
                    log.info("force expire " + status.getAssignSessionId());
                    //强制1分钟过期一个sessionId
                    closeSession(myChrome.getSlotStatus().getBelongsToUri(), status.getAssignSessionId());
                    status.setAssignSessionId(null);
                    myChrome.setClientSessionId(null);
                    create = false;
                } else {
                    return status;
                }
            }
        }
        if (create) {
            log.info("开始创建sessionId ");
            if (chromes != null) {
                for (MyChrome myChrome : chromes) {
                    String oldClientSessionId = myChrome.getClientSessionId() == null ? null : myChrome.getClientSessionId();
                    log.info("当前sessionId = " + myChrome.getWebDriver().getSessionId().toString() + ", oldClientSessionId = " + oldClientSessionId);
                    if (oldClientSessionId == null) {
                        String s = myChrome.getWebDriver().getSessionId().toString();
                        status.setAssignSessionId(s);
                        status.setNew(true);
//                    redisTemplate.opsForValue().set("servlet:session:" + servletSessionId, s, 300, TimeUnit.SECONDS);
                        cacheUtil.put("servlet:session:" + servletSessionId, new StringCache(System.currentTimeMillis(), s, 300), 300);
                        return status;
                    }
                }
            }
        }
        return status;
    }

    public synchronized void releaseWebDriver(String input) {
//        redisTemplate.delete(CLIENT_SESSION_ID_KEY + ":" + input);
        cacheUtil.remove(CLIENT_SESSION_ID_KEY + ":" + input);
        log.info("releaseWebDriver " + input);
        Iterator<MyChrome> iterator = chromes.iterator();
        while (iterator.hasNext()) {
            MyChrome myChrome = iterator.next();
            String sessionId = myChrome.getWebDriver().getSessionId().toString();
            if (sessionId.equals(input)) {
                String uri = myChrome.getSlotStatus().getBelongsToUri();
                try {
                    myChrome.getWebDriver().quit();
                    iterator.remove();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                log.info("destroy chrome : " + uri + "-->" + sessionId);
                break;
            }
        }
    }

    public synchronized void bindSessionId(String sessionId) {
        for (MyChrome myChrome : chromes) {
            if (myChrome != null && myChrome.getWebDriver().getSessionId().toString().equals(sessionId)) {
                myChrome.setClientSessionId(sessionId);
//                redisTemplate.opsForValue().set(CLIENT_SESSION_ID_KEY + ":" + sessionId, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), opTimeout, TimeUnit.SECONDS);
                cacheUtil.put(CLIENT_SESSION_ID_KEY + ":" + sessionId, new StringCache(System.currentTimeMillis(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), opTimeout), opTimeout);
                break;
            }
        }
    }

    public synchronized void unBindSessionId(String sessionId, String servletSessionId) {
        Iterator<MyChrome> iterator = chromes.iterator();
//        redisTemplate.delete("servlet:session:" + servletSessionId);
        cacheUtil.remove("servlet:session:" + servletSessionId);
        while (iterator.hasNext()) {
            MyChrome myChrome = iterator.next();
            if (myChrome != null && myChrome.getWebDriver().getSessionId().toString().equals(sessionId)) {
                myChrome.setClientSessionId(null);
//                redisTemplate.delete(CLIENT_SESSION_ID_KEY + ":" + sessionId);
                cacheUtil.remove(CLIENT_SESSION_ID_KEY + ":" + sessionId);
                iterator.remove();
                break;
            }
        }
    }

    public String getXddUrl() {
        return xddUrl;
    }

    public String getXddToken() {
        return xddToken;
    }

    public List<QLConfig> getQlConfigs() {
        return qlConfigs;
    }

    public Properties getProperties() {
        return properties;
    }

    public boolean isInitSuccess() {
        return initSuccess;
    }
}
