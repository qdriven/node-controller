package io.metersphere.node.controller;


import com.github.dockerjava.api.model.Container;
import io.metersphere.node.controller.request.TestRequest;
import io.metersphere.node.service.JmeterOperateService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("jmeter")
public class JmeterOperateController {
    @Resource
    private JmeterOperateService jmeterOperateService;

    /**
     * 初始化测试任务，根据需求启动若干个 JMeter Engine 容器
     */
    @PostMapping("/container/start")
    public String startContainer(@RequestBody TestRequest testRequest) throws IOException {
        jmeterOperateService.startContainer(testRequest);
        return "OK";
    }

    /**
     * 停止指定测试任务，控制上述容器停止指定的 JMeter 测试
     */
    @GetMapping("container/stop/{testId}")
    public String stopContainer(@PathVariable String testId) {
        jmeterOperateService.stopContainer(testId);
        return "OK";
    }

    @GetMapping("download/jtl/{reportId}")
    public ResponseEntity<byte[]> downloadJtl(@PathVariable String reportId) {
        byte[] bytes = jmeterOperateService.downloadJtl(reportId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/octet-stream"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + reportId + ".jtl" + "\"")
                .body(bytes);
    }

    @GetMapping("delete/jtl/{reportId}")
    public boolean deleteJtl(@PathVariable String reportId) {
        return jmeterOperateService.deleteJtl(reportId);
    }

    /**
     * 停止指定测试任务，控制上述容器停止指定的 JMeter 测试
     */
    @GetMapping("container/log/{testId}")
    public String logContainer(@PathVariable String testId) {
        return jmeterOperateService.logContainer(testId);
    }

    // 查询测试任务状态，控制上述容器执行相关命令查询 JMeter 测试状态
    @GetMapping("/task/status/{testId}")
    public List<Container> getTaskStatus(@PathVariable String testId) {
        return jmeterOperateService.taskStatus(testId);
    }

}
