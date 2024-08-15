# log-viewer

#### 介绍
通过web查看服务端日志

#### 软件架构
1. Netty实现Websocket实现实时传输服务端日志
2. Graalvm编译为本地执行文件加快启动速度,降低内存占用

#### 构建
1. 环境安装参考<a href="https://www.graalvm.org/latest/reference-manual/native-image/">Graalvm Native</a>
2. Maven 构建命令
   ```shell
   mvn native:compile -f pom.xml
   ```

#### 使用说明

1. 配置配置文件config.yaml (配置文件放到执行文件目录)
    ```yaml
    port: 8080 #端口
    files:
      test: #服务端名称
        label: "测试" 服务端选项名称
        path: "test.log" 服务端日志路径
    ```