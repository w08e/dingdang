package com.example.sse.service.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.sse.dto.HospitalRequest;
import com.example.sse.dto.HospitalResponse;
import com.example.sse.model.Hospital;
import com.example.sse.service.HospitalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;

/**
 * 医院服务实现类
 */
@Slf4j
@Service
public class HospitalServiceImpl implements HospitalService {

    private final ResourceLoader resourceLoader;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 缓存最近查询过的医院位置信息
    private final Map<String, Hospital> hospitalLocationCache = new ConcurrentHashMap<>();

    // 记录获取位置失败的医院
    private final Set<Hospital> failedLocationHospitals = ConcurrentHashMap.newKeySet();

    // 医院数据
    private List<Hospital> allHospitals = new ArrayList<>();
    private Set<String> availableProvinces = new HashSet<>();
    private Map<String, Set<String>> availableCities = new HashMap<>();

//    @Value("${amap.key:7da2a87f1537a1ee884ea153d98616ae}")
    @Value("${amap.key:e6ad3a61b17443e1c12190bdda1e2bb5}")
    private String amapKey;

    // 使用令牌桶限流，确保API调用不超过限制
    private final Semaphore apiSemaphore = new Semaphore(3); // 限制并发为3个
    private final long minApiInterval = 1000; // 每次API调用之间的最小间隔（毫秒）
    private long lastApiCallTime = 0;

    // 使用默认路径，无需配置文件
    private String hospitalDataPath = "classpath:static/hospital.json";

    // 位置数据加载状态
    private boolean isLoadingLocations = false;
    private int totalLocationsToLoad = 0;
    private int loadedLocationsCount = 0;

    // 线程池用于异步加载位置数据，降低线程数量
    private final Executor locationLoadExecutor = Executors.newFixedThreadPool(3);

    public HospitalServiceImpl(ResourceLoader resourceLoader, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时加载医院数据和位置信息
     */
    @PostConstruct
    public void init() {
        loadHospitalData();
        // 启动时开始加载位置数据
        CompletableFuture.runAsync(this::loadAllHospitalLocations, locationLoadExecutor);
    }

    /**
     * 每天凌晨3点刷新医院数据和位置缓存
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @CacheEvict(value = "hospitalLocations", allEntries = true)
    public void refreshData() {
        // 监测路径hospitalDataPath文件大小是否改变
        Resource resource = resourceLoader.getResource(hospitalDataPath);
        try {
            if (resource.exists() && resource.isFile()) {
                long fileSize = resource.getFile().length();
                log.info("医院数据文件大小: {} bytes", fileSize);
                if (fileSize != 384812){
                    loadHospitalData();
                    // 重新加载位置信息
                    CompletableFuture.runAsync(this::loadAllHospitalLocations, locationLoadExecutor);
                }
            } else {
                log.warn("医院数据文件不存在或不是文件");
            }
        } catch (IOException e) {
            log.error("获取医院数据文件大小时出错", e);
        }
        log.info("定时刷新医院数据");
        hospitalLocationCache.clear();
        failedLocationHospitals.clear();

    }

    /**
     * 每小时重试获取失败的医院位置
     */
    @Scheduled(fixedRate = 3600000) // 每小时执行一次
    public void retryFailedLocations() {
        if (failedLocationHospitals.isEmpty()) {
            log.info("没有需要重试的医院位置");
            return;
        }

        log.info("开始重试获取{}家医院的位置信息", failedLocationHospitals.size());
        Set<Hospital> hospitalsToRetry = new HashSet<>(failedLocationHospitals);
        failedLocationHospitals.clear(); // 清空失败列表，重试过程中失败的会被重新添加

        CompletableFuture.runAsync(() -> {
            for (Hospital hospital : hospitalsToRetry) {
                try {
                    waitForApiRateLimit();
                    boolean acquired = apiSemaphore.tryAcquire(5, TimeUnit.SECONDS);
                    if (acquired) {
                        try {
                            log.info("重试获取医院[{}]的位置", hospital.getName());
                            Hospital result = loadHospitalLocationWithRetry(hospital, 3);
                            if (!result.getHasAccurateLocation()) {
                                failedLocationHospitals.add(hospital);
                            }
                        } finally {
                            apiSemaphore.release();
                        }
                    } else {
                        failedLocationHospitals.add(hospital);
                    }
                } catch (Exception e) {
                    log.error("重试获取医院[{}]位置时出错", hospital.getName(), e);
                    failedLocationHospitals.add(hospital);
                }
            }
            log.info("重试完成，还有{}家医院位置获取失败", failedLocationHospitals.size());
        }, locationLoadExecutor);
    }

    @Override
    public void loadHospitalData() {
        try {
            log.info("开始加载医院数据，路径: {}", hospitalDataPath);

            // 尝试多个可能的路径
            String[] possiblePaths = {
                hospitalDataPath,
                "classpath:static/医院.json",
                "classpath:static/hospital.json",
                "classpath:/static/医院.json",
                "classpath:/static/hospital.json",
                "file:医院.json",
                "file:hospital.json",
                "/医院.json",
                "/hospital.json"
            };

            Resource resource = null;
            String usedPath = null;

            // 尝试所有可能的路径
            for (String path : possiblePaths) {
                log.info("尝试加载路径: {}", path);
                Resource tempResource = resourceLoader.getResource(path);
                if (tempResource.exists()) {
                    resource = tempResource;
                    usedPath = path;
                    log.info("找到资源文件: {}", path);
                    break;
                } else {
                    log.warn("路径不存在: {}", path);
                }
            }

            if (resource == null || !resource.exists()) {
                log.error("所有尝试的路径都未找到医院数据文件");
                return;
            }

            log.info("成功找到医院数据文件: {}", usedPath);

            try (InputStream inputStream = resource.getInputStream()) {
                // 读取JSON数据
                List<Hospital> hospitals = objectMapper.readValue(inputStream, new TypeReference<List<Hospital>>() {});

                log.info("成功读取医院数据，原始记录数: {}", hospitals.size());

                // 跳过第一条数据，因为它可能是表头
                if (hospitals.size() > 1) {
                    hospitals = hospitals.subList(1, hospitals.size());
                }

                // 过滤、标准化数据
                allHospitals = hospitals.stream()
                        .filter(hospital -> hospital.get医院() != null && !hospital.get医院().isEmpty())
                        .peek(hospital -> {
                            // 添加详细日志
                            if (hospital.get城市地级市() == null) {
                                log.warn("医院[{}]的城市字段为null，原始JSON数据可能有问题", hospital.get医院());
                            }
                            hospital.standardize();
                            if (hospital.getCity() == null) {
                                log.warn("医院[{}]标准化后的城市字段为null", hospital.getName());
                            }
                        })
                        .collect(Collectors.toList());

                log.info("处理后的医院数据条数: {}", allHospitals.size());

                // 输出前5条记录的城市信息作为示例
                log.info("前5条医院记录示例:");
                allHospitals.stream().limit(5).forEach(hospital -> {
                    log.info("医院: {}, 省份: {}, 城市: {}",
                        hospital.getName(), hospital.getProvince(), hospital.getCity());
                });

                // 提取所有可用的省份和城市
                availableProvinces = allHospitals.stream()
                        .map(Hospital::getProvince)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toSet());

                // 为每个省添加城市
                availableCities = new HashMap<>();
                allHospitals.forEach(hospital -> {
                    String province = hospital.getProvince();
                    String city = hospital.getCity();
                    if (StringUtils.hasText(province) && StringUtils.hasText(city)) {
                        availableCities.computeIfAbsent(province, k -> new HashSet<>()).add(city);
                    }
                });

                log.info("可用省份数量: {}", availableProvinces.size());
            }
        } catch (IOException e) {
            log.error("加载医院数据失败", e);
        }
    }

    /**
     * 加载所有医院位置信息
     */
    private void loadAllHospitalLocations() {
        log.info("开始加载所有医院位置信息");
        isLoadingLocations = true;

        try {
            // 筛选出没有精确位置的医院
            List<Hospital> hospitalsToLoad = allHospitals.stream()
                    .filter(hospital -> !hospital.getHasAccurateLocation())
                    .collect(Collectors.toList());

            totalLocationsToLoad = hospitalsToLoad.size();
            loadedLocationsCount = 0;

            log.info("需要加载位置信息的医院数量: {}", totalLocationsToLoad);

            if (totalLocationsToLoad == 0) {
                log.info("没有需要加载位置信息的医院");
                return;
            }

            // 小批量处理，降低并发压力
            int batchSize = 5;
            int totalBatches = (hospitalsToLoad.size() + batchSize - 1) / batchSize;

            for (int i = 0; i < totalBatches; i++) {
                int fromIndex = i * batchSize;
                int toIndex = Math.min(fromIndex + batchSize, hospitalsToLoad.size());
                List<Hospital> batch = hospitalsToLoad.subList(fromIndex, toIndex);

                log.info("处理第{}/{}批医院位置信息，本批次{}家", i + 1, totalBatches, batch.size());

                CountDownLatch batchLatch = new CountDownLatch(batch.size());

                for (Hospital hospital : batch) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            waitForApiRateLimit();
                            boolean acquired = apiSemaphore.tryAcquire(5, TimeUnit.SECONDS);
                            if (acquired) {
                                try {
                                    Hospital result = loadHospitalLocationWithRetry(hospital, 3);
                                    if (!result.getHasAccurateLocation()) {
                                        failedLocationHospitals.add(hospital);
                                    }
                                } finally {
                                    apiSemaphore.release();
                                }
                            } else {
                                failedLocationHospitals.add(hospital);
                            }

                            synchronized (this) {
                                loadedLocationsCount++;
                                if (loadedLocationsCount % 10 == 0 || loadedLocationsCount == totalLocationsToLoad) {
                                    log.info("加载位置信息进度: {}/{} ({}%)",
                                            loadedLocationsCount, totalLocationsToLoad,
                                            Math.round((double) loadedLocationsCount / totalLocationsToLoad * 100));
                                }
                            }
                        } catch (Exception e) {
                            log.error("加载医院[{}]位置信息时出错", hospital.getName(), e);
                            failedLocationHospitals.add(hospital);
                        } finally {
                            batchLatch.countDown();
                        }
                    }, locationLoadExecutor);
                }

                // 等待当前批次完成
                batchLatch.await(2, TimeUnit.MINUTES);

                // 批次间增加延迟
                Thread.sleep(2000);
            }

            log.info("医院位置信息加载完成，共加载{}家医院，{}家失败",
                    totalLocationsToLoad - failedLocationHospitals.size(),
                    failedLocationHospitals.size());

        } catch (Exception e) {
            log.error("加载医院位置信息时发生错误", e);
        } finally {
            isLoadingLocations = false;
        }
    }

    /**
     * 确保API调用不超过频率限制
     */
    private synchronized void waitForApiRateLimit() throws InterruptedException {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCall = currentTime - lastApiCallTime;

        if (timeSinceLastCall < minApiInterval) {
            Thread.sleep(minApiInterval - timeSinceLastCall);
        }

        lastApiCallTime = System.currentTimeMillis();
    }

    /**
     * 按需加载医院位置信息
     * 当用户请求医院列表时触发
     * 优先处理请求区域的医院
     */
    private void loadHospitalLocationsOnDemand(String province, String city) {
        // 如果已经在全量加载过程中，则不再按需加载
        if (isLoadingLocations) {
            log.info("医院位置信息正在全量加载中，跳过按需加载");
            return;
        }

        try {
            log.info("开始按需加载{}{}医院位置信息",
                    StringUtils.hasText(province) ? province : "",
                    StringUtils.hasText(city) ? city : "");

            // 标记为加载中
            isLoadingLocations = true;

            // 筛选出指定省市下没有经纬度的医院
            List<Hospital> hospitalsToLoad = allHospitals.stream()
                    .filter(hospital -> {
                        // 检查省份匹配
                        boolean provinceMatch = !StringUtils.hasText(province) ||
                                (hospital.getProvince() != null && hospital.getProvince().equals(province));

                        // 检查城市匹配
                        boolean cityMatch = !StringUtils.hasText(city) ||
                                (hospital.getCity() != null && hospital.getCity().equals(city));

                        // 检查是否已有经纬度
                        boolean needsLocation = !hospital.getHasAccurateLocation();

                        return provinceMatch && cityMatch && needsLocation;
                    })
                    .collect(Collectors.toList());

            // 初始化计数器
            totalLocationsToLoad = hospitalsToLoad.size();
            loadedLocationsCount = 0;

            if (totalLocationsToLoad == 0) {
                log.info("{}{}没有需要加载位置信息的医院",
                        StringUtils.hasText(province) ? province : "",
                        StringUtils.hasText(city) ? city : "");
                return;
            }

            log.info("{}{}共有{}家医院需要获取位置信息",
                    StringUtils.hasText(province) ? province : "",
                    StringUtils.hasText(city) ? city : "",
                    totalLocationsToLoad);

            // 分批处理
            int batchSize = 5;
            int totalBatches = (hospitalsToLoad.size() + batchSize - 1) / batchSize;

            for (int i = 0; i < totalBatches; i++) {
                int fromIndex = i * batchSize;
                int toIndex = Math.min(fromIndex + batchSize, hospitalsToLoad.size());
                List<Hospital> batch = hospitalsToLoad.subList(fromIndex, toIndex);

                log.info("处理第{}/{}批医院位置信息，本批次{}家",
                        i + 1, totalBatches, batch.size());

                CountDownLatch batchLatch = new CountDownLatch(batch.size());

                for (Hospital hospital : batch) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            waitForApiRateLimit();
                            boolean acquired = apiSemaphore.tryAcquire(5, TimeUnit.SECONDS);
                            if (acquired) {
                                try {
                                    Hospital result = loadHospitalLocationWithRetry(hospital, 3);
                                    if (!result.getHasAccurateLocation()) {
                                        failedLocationHospitals.add(hospital);
                                    }
                                } finally {
                                    apiSemaphore.release();
                                }
                            } else {
                                failedLocationHospitals.add(hospital);
                            }

                            synchronized (this) {
                                loadedLocationsCount++;
                            }
                        } catch (Exception e) {
                            log.error("加载医院[{}]位置信息时出错", hospital.getName(), e);
                            failedLocationHospitals.add(hospital);
                        } finally {
                            batchLatch.countDown();
                        }
                    }, locationLoadExecutor);
                }

                // 等待当前批次完成，设置超时
                batchLatch.await(2, TimeUnit.MINUTES);

                // 批次间增加延迟
                Thread.sleep(2000);
            }

        } catch (Exception e) {
            log.error("加载医院位置信息时发生错误", e);
        } finally {
            isLoadingLocations = false;
        }
    }

    /**
     * 带重试机制的医院位置加载
     * @param hospital 医院对象
     * @param maxRetries 最大重试次数
     * @return 更新后的医院对象
     */
    private Hospital loadHospitalLocationWithRetry(Hospital hospital, int maxRetries) {
        int retries = 0;
        boolean success = false;
        Exception lastException = null;

        while (retries <= maxRetries && !success) {
            try {
                if (retries > 0) {
                    log.info("尝试第{}次重试获取医院[{}]位置信息", retries, hospital.getName());
                    // 添加退避时间，指数退避
                    Thread.sleep(1000 * (long)Math.pow(2, retries));
                }

                Hospital updatedHospital = getHospitalLocation(hospital);
                success = updatedHospital.getHasAccurateLocation();

                if (success) {
                    log.info("成功获取医院[{}]位置信息，经纬度: [{}, {}]",
                            hospital.getName(),
                            updatedHospital.getLongitude(),
                            updatedHospital.getLatitude());
                    return updatedHospital;
                } else if (retries < maxRetries) {
                    log.info("未能获取医院[{}]位置信息，准备重试", hospital.getName());
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("获取医院[{}]位置信息失败，异常: {}", hospital.getName(), e.getMessage());
            }

            retries++;
        }

        if (!success && lastException != null) {
            log.error("在{}次尝试后仍未能获取医院[{}]位置信息", maxRetries, hospital.getName(), lastException);
        }

        return hospital;
    }

    @Override
    public List<String> getAllProvinces() {
        return new ArrayList<>(availableProvinces).stream().sorted().collect(Collectors.toList());
    }

    @Override
    public List<String> getCitiesByProvince(String province) {
        if (!StringUtils.hasText(province) || !availableCities.containsKey(province)) {
            log.warn("请求了不存在的省份[{}]的城市列表", province);
            return new ArrayList<>();
        }
        List<String> cities = new ArrayList<>(availableCities.get(province)).stream().sorted().collect(Collectors.toList());
        log.info("省份[{}]的城市列表: {}", province, cities);
        return cities;
    }

    @Override
    public HospitalResponse getHospitalsByCondition(HospitalRequest request) throws InterruptedException {
        log.info("查询医院，条件: {}", request);

        // 根据筛选条件过滤医院数据
        List<Hospital> filteredHospitals = allHospitals;

        // 按省份筛选
        if (StringUtils.hasText(request.getProvince())) {
            filteredHospitals = filteredHospitals.stream()
                    .filter(hospital -> request.getProvince().equals(hospital.getProvince()))
                    .collect(Collectors.toList());
            log.info("按省份{}筛选后: {}", request.getProvince(), filteredHospitals.size());
        }

        // 按城市筛选
        if (StringUtils.hasText(request.getCity())) {
            // 添加详细日志，输出城市为null的医院数量
            long nullCityCount = filteredHospitals.stream()
                    .filter(hospital -> hospital.getCity() == null)
                    .count();
            if (nullCityCount > 0) {
                log.warn("有{}家医院的城市字段为null", nullCityCount);
            }

            filteredHospitals = filteredHospitals.stream()
                    .filter(hospital -> {
                        if (hospital.getCity() == null) {
                            return false;
                        }
                        return request.getCity().equals(hospital.getCity());
                    })
                    .collect(Collectors.toList());
            log.info("按城市{}筛选后: {}", request.getCity(), filteredHospitals.size());
        }

        // 按关键词搜索
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().toLowerCase();
            filteredHospitals = filteredHospitals.stream()
                    .filter(hospital ->
                            (hospital.getName() != null && hospital.getName().toLowerCase().contains(keyword)) ||
                            (hospital.getAddress() != null && hospital.getAddress().toLowerCase().contains(keyword)))
                    .collect(Collectors.toList());
            log.info("按关键词{}筛选后: {}", request.getKeyword(), filteredHospitals.size());
        }

        // 检查特殊地区
        if (isSpecialRegion(request.getProvince()) || isSpecialRegion(request.getCity()) || isSpecialRegion(request.getKeyword())) {
            return new HospitalResponse("很抱歉，暂无法提供该地区的医院信息。您可以尝试搜索其他地区，或联系客服获取更多帮助。");
        }

        // 检查数据是否为空
        if (filteredHospitals.isEmpty()) {
            return new HospitalResponse("未找到匹配的医院，请尝试其他关键词或选择其他省市。");
        }

        // 根据用户位置计算并排序医院
        if (request.getLongitude() != null && request.getLatitude() != null) {
            double userLng = request.getLongitude();
            double userLat = request.getLatitude();
            log.info("使用用户位置排序: [{}, {}]", userLng, userLat);

            // 只处理当前页面数据的位置信息
            int page = request.getPage();
            int size = request.getSize();
            int total = filteredHospitals.size();

            int fromIndex = page * size;
            int toIndex = Math.min(fromIndex + size, total);

            if (fromIndex < total) {
                // 获取当前页的医院列表
                List<Hospital> currentPageHospitals = filteredHospitals.subList(fromIndex, toIndex);

                // 优先处理当前页的医院位置信息
                for (Hospital hospital : currentPageHospitals) {
                    if (!hospital.getHasAccurateLocation()) {
                        try {
                            boolean acquired = apiSemaphore.tryAcquire(2, TimeUnit.SECONDS);
                            if (acquired) {
                                try {
                                    getHospitalLocation(hospital);
                                } finally {
                                    apiSemaphore.release();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                // 异步处理其他医院
                CompletableFuture.runAsync(() ->
                    loadHospitalLocationsOnDemand(request.getProvince(), request.getCity()),
                    locationLoadExecutor);
            }
            // 统计数据
            int hospitalCount = filteredHospitals.size();
            int hospitalsWithLocation = 0;
            long startTime = System.currentTimeMillis();

            // 计算距离
            for (Hospital hospital : filteredHospitals) {
                if (hospital.getHasAccurateLocation() && hospital.getLongitude() != null && hospital.getLatitude() != null) {
                    log.info("使用已有经纬度计算距离,医院[{}]的经纬度: [{}, {}]", hospital.getName(), hospital.getLongitude(), hospital.getLatitude());
                    // 使用已有经纬度计算距离
                    double distance = calculateDistance(
                            userLat, userLng,
                            hospital.getLatitude(), hospital.getLongitude()
                    );
                    hospital.setDistance(distance);
                    hospitalsWithLocation++;

                    log.info("医院[{}]的距离计算完成: {}米, 经纬度: [{}, {}]",
                            hospital.getName(), Math.round(distance),
                            hospital.getLongitude(), hospital.getLatitude());
                } else {
                    // 尝试获取经纬度
                    log.info("尝试获取医院[{}]的经纬度", hospital.getName());
                    Hospital updatedHospital = getHospitalLocation(hospital);
                    if (updatedHospital.getHasAccurateLocation()) {
                        hospital.setLongitude(updatedHospital.getLongitude());
                        hospital.setLatitude(updatedHospital.getLatitude());
                        hospital.setHasAccurateLocation(true);
                        double distance = calculateDistance(
                                userLat, userLng,
                                hospital.getLatitude(), hospital.getLongitude()
                        );
                        hospital.setDistance(distance);
                        hospitalsWithLocation++;

                        log.info("医院[{}]位置获取成功，距离计算完成: {}米, 经纬度: [{}, {}]",
                                hospital.getName(), Math.round(distance),
                                hospital.getLongitude(), hospital.getLatitude());
                    } else {
                        log.info("使用估计距离，医院[{}]位置未知", hospital.getName());
                        // 使用估计距离
                        double randomDistance = Math.round(Math.random() * 10000);
                        hospital.setDistance(randomDistance);
                        hospital.setHasAccurateLocation(false);

                        log.info("医院[{}]位置未知，使用估算距离: {}米", hospital.getName(), Math.round(randomDistance));
                    }
                }
            }

            long timeElapsed = System.currentTimeMillis() - startTime;
            log.info("距离计算完成，耗时: {}ms, 共{}家医院，其中{}家有精确位置 ({}%)",
                    timeElapsed, hospitalCount, hospitalsWithLocation,
                    Math.round((double) hospitalsWithLocation / hospitalCount * 100));

            // 按距离排序
            filteredHospitals.sort((a, b) -> {
                // 优先考虑有准确位置的医院
                if (a.getHasAccurateLocation() && !b.getHasAccurateLocation()) return -1;
                if (!a.getHasAccurateLocation() && b.getHasAccurateLocation()) return 1;

                // 其次按距离排序
                return a.getDistance().compareTo(b.getDistance());
            });
        }

        // 分页处理
        int totalRecords = filteredHospitals.size();
        int page = request.getPage();
        int size = request.getSize();

        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, totalRecords);

        if (fromIndex >= totalRecords) {
            return new HospitalResponse(Collections.emptyList(), totalRecords, page, size, "没有更多数据");
        }

        List<Hospital> pagedHospitals = filteredHospitals.subList(fromIndex, toIndex);

        // 输出分页结果
        log.info("返回第{}页数据，每页{}条，共{}条记录", page + 1, size, totalRecords);

        return new HospitalResponse(pagedHospitals, totalRecords, page, size, null);
    }

    @Override
    @Cacheable(value = "hospitalLocations", key = "#hospital.fullAddress")
    public Hospital getHospitalLocation(Hospital hospital) {
        String cacheKey = hospital.getFullAddress();
        log.info("获取医院[{}]的位置信息，地址: {}", hospital.getName(), cacheKey);

        // 先查本地缓存
        if (hospitalLocationCache.containsKey(cacheKey)) {
            Hospital cachedHospital = hospitalLocationCache.get(cacheKey);
            log.info("命中缓存，医院[{}]的经纬度: [{}, {}]",
                    hospital.getName(), cachedHospital.getLongitude(), cachedHospital.getLatitude());
            hospital.setLongitude(cachedHospital.getLongitude());
            hospital.setLatitude(cachedHospital.getLatitude());
            hospital.setHasAccurateLocation(cachedHospital.getHasAccurateLocation());
            return hospital;
        }

        // 如果没有地址，无法获取位置
        if (!StringUtils.hasText(hospital.getFullAddress())) {
            log.warn("医院[{}]缺少地址信息，无法获取位置", hospital.getName());
            hospital.setHasAccurateLocation(false);
            return hospital;
        }

        // 构建不同的查询地址组合，按优先级尝试
        List<String> addressQueries = buildAddressQueries(hospital);

        for (String addressQuery : addressQueries) {
            try {
                // 构建请求参数
                HashMap<String, Object> params = new HashMap<>();
                params.put("address", addressQuery);
                params.put("key", amapKey);
                params.put("output", "json");

                // 使用Hutool发送请求
                String result = HttpUtil.get("https://restapi.amap.com/v3/geocode/geo", params);

                // 解析响应
                JSONObject data = JSONUtil.parseObj(result);
                log.info("获取经纬度api, 请求位置参数为{}, 返回位置为{}", addressQuery, data.get("formatted_address"));
                log.info("获取经纬度api，参数为：{},原始返回参数为{}", amapKey, data);

                if ("1".equals(data.getStr("status")) && data.containsKey("geocodes")) {
                    JSONArray geocodes = data.getJSONArray("geocodes");
                    if (geocodes != null && !geocodes.isEmpty()) {
                        JSONObject geocode = geocodes.getJSONObject(0);
                        String location = geocode.getStr("location");

                        if (StringUtils.hasText(location)) {
                            String[] coordinates = location.split(",");
                            if (coordinates.length == 2) {
                                double lng = Double.parseDouble(coordinates[0]);
                                double lat = Double.parseDouble(coordinates[1]);

                                log.info("成功获取医院[{}]位置，经纬度: [{}, {}]，使用地址: {}",
                                        hospital.getName(), lng, lat, addressQuery);

                                hospital.setLongitude(lng);
                                hospital.setLatitude(lat);
                                hospital.setHasAccurateLocation(true);

                                // 更新缓存
                                hospitalLocationCache.put(cacheKey, hospital);
                                return hospital;
                            }
                        }
                    }
                }
                Thread.sleep(2000);
                log.warn("使用地址[{}]查询医院[{}]位置失败，尝试下一个地址组合",
                        addressQuery, hospital.getName());

            } catch (Exception e) {
                log.error("查询医院[{}]位置信息出错，地址: {}", hospital.getName(), addressQuery, e);
            }
        }

        log.warn("所有地址组合均未能获取到医院[{}]的位置信息", hospital.getName());
        hospital.setHasAccurateLocation(false);
        return hospital;
    }

    /**
     * 构建多种地址组合用于查询
     * 按照从具体到模糊的顺序尝试
     */
    private List<String> buildAddressQueries(Hospital hospital) {
        List<String> queries = new ArrayList<>();

        // 原始完整地址
        String fullAddress = hospital.getFullAddress();
        if (StringUtils.hasText(fullAddress)) {
            queries.add(fullAddress);
        }

        // 省市县+详细地址
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(hospital.getProvince())) {
            sb.append(hospital.getProvince());
        }
        if (StringUtils.hasText(hospital.getCity())) {
            sb.append(hospital.getCity());
        }
        if (StringUtils.hasText(hospital.getDistrict()) && !hospital.getDistrict().equals(" ")) {
            sb.append(hospital.getDistrict());
        }
        if (StringUtils.hasText(hospital.getAddress())) {
            sb.append(hospital.getAddress());
        }
        String addressWithoutSpace = sb.toString();
        if (!queries.contains(addressWithoutSpace) && StringUtils.hasText(addressWithoutSpace)) {
            queries.add(addressWithoutSpace);
        }

        // 只使用医院名称和城市
        if (StringUtils.hasText(hospital.getCity()) && StringUtils.hasText(hospital.getName())) {
            String cityHospital = hospital.getCity() + hospital.getName();
            if (!queries.contains(cityHospital)) {
                queries.add(cityHospital);
            }
        }

        // 只使用完整医院名称
        if (StringUtils.hasText(hospital.getName()) && !queries.contains(hospital.getName())) {
            queries.add(hospital.getName());
        }

        return queries;
    }

    /**
     * 计算两点之间的距离（单位：米）
     * 使用Haversine公式计算球面两点间的距离
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371000; // 地球半径，单位米

        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);

        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double distance = R * c;

        log.trace("计算距离 - 点1[{}, {}] 到 点2[{}, {}] = {}米",
                lat1, lng1, lat2, lng2, Math.round(distance));

        return distance;
    }

    /**
     * 检查是否为特殊地区
     */
    private boolean isSpecialRegion(String region) {
        if (!StringUtils.hasText(region)) {
            return false;
        }

        String[] specialRegions = {"台湾", "香港", "澳门", "厦门"};
        for (String specialRegion : specialRegions) {
            if (region.contains(specialRegion)) {
                return true;
            }
        }

        return false;
    }

    public static void main(String[] args) throws IOException {
        // 获取hospitalDataPath路径下的文件
        String hospitalDataPath = "classpath:static/hospital.json";
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        Resource resource = resourceLoader.getResource(hospitalDataPath);
        System.out.println(resource.getFile().length());
    }
} 