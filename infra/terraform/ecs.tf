##############################################################################
# ECS Fargate。5 つのサービス。
#
# ★ voice-media を voice-web と分けるのがこの構成の要点。
#   Media Streams は 1 通話あたり毎秒 50 メッセージ。同居させると
#   同時通話が増えるほど webhook の応答が遅れ、Twilio がタイムアウトして
#   再送を始める。負荷が高いときに、いちばん壊れてほしくない経路
#   （通話の状態記録）が最初に壊れる。
#
# ★ voice-jobs は ALB に繋がない。HTTP を持たないので、
#   ターゲットグループを付けると永久にヘルスチェック待ちになる。
##############################################################################

resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "main" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}

# ---------------------------------------------------------------- IAM

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# ★ 実行ロールに Secrets の読み取りを与える。タスクロールではなく
#   実行ロールなのは、コンテナ起動時に ECS エージェントが解決するため
data "aws_iam_policy_document" "secrets_read" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.app.arn,
      aws_secretsmanager_secret.db_app.arn,
      aws_db_instance.main.master_user_secret[0].secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${local.name}-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.secrets_read.json
}

resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# ★ 録音バケットへの権限は必要最小限。
#   ListBucket を与えないので、鍵を知らないオブジェクトは列挙できない
data "aws_iam_policy_document" "task_s3" {
  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.recordings.arn}/*"]
  }
}

resource "aws_iam_role_policy" "task_s3" {
  name   = "${local.name}-s3"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_s3.json
}

# ---------------------------------------------------------------- ALB

resource "aws_lb" "main" {
  name               = local.name
  load_balancer_type = "application"
  subnets            = [for s in aws_subnet.public : s.id]
  security_groups    = [aws_security_group.alb.id]

  # ★ 通話中の WebSocket が切れないよう長めにする。
  #   既定の 60 秒だと、無音が続いた通話で Media Stream が切断される
  idle_timeout = 3600

  enable_deletion_protection = true
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

locals {
  # サービスごとのターゲットグループ設定
  http_services = {
    api = {
      port        = 8080
      health_path = "/actuator/health"
      priority    = 10
      # ★ 業務 API
      path_patterns = ["/api/v1/*"]
    }
    voice-web = {
      port        = 8001
      health_path = "/healthz"
      priority    = 20
      # ★ Twilio の webhook と内部 API
      path_patterns = ["/twilio/*", "/internal/*"]
    }
    voice-media = {
      port        = 8001
      health_path = "/healthz"
      priority    = 30
      # ★ Media Streams の WebSocket。専用サービスへ振る
      path_patterns = ["/media"]
    }
    web = {
      port        = 3000
      health_path = "/"
      priority    = 100
      path_patterns = ["/*"]
    }
  }
}

resource "aws_lb_target_group" "this" {
  for_each = { for k, v in local.http_services : k => v if k != "web" }

  name        = "${local.name}-${each.key}"
  port        = each.value.port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = each.value.health_path
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  # ★ 通話中のタスクを即座に落とさない。デプロイのたびに通話が切れる
  deregistration_delay = 120
}

resource "aws_lb_target_group" "web" {
  name        = "${local.name}-web"
  port        = 3000
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = "/"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }

  deregistration_delay = 30
}

resource "aws_lb_listener_rule" "this" {
  for_each = { for k, v in local.http_services : k => v if k != "web" }

  listener_arn = aws_lb_listener.https.arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }

  condition {
    path_pattern {
      values = each.value.path_patterns
    }
  }
}

# ---------------------------------------------------------------- タスク定義

locals {
  # ★ 秘密情報は値ではなく ARN の参照で渡す。タスク定義に平文を書かない
  app_secrets = [
    { name = "JWT_SECRET", valueFrom = "${aws_secretsmanager_secret.app.arn}:JWT_SECRET::" },
    { name = "TWILIO_ACCOUNT_SID", valueFrom = "${aws_secretsmanager_secret.app.arn}:TWILIO_ACCOUNT_SID::" },
    { name = "TWILIO_AUTH_TOKEN", valueFrom = "${aws_secretsmanager_secret.app.arn}:TWILIO_AUTH_TOKEN::" },
    { name = "TWILIO_CALLER_ID", valueFrom = "${aws_secretsmanager_secret.app.arn}:TWILIO_CALLER_ID::" },
    { name = "ASR_API_KEY", valueFrom = "${aws_secretsmanager_secret.app.arn}:ASR_API_KEY::" },
    { name = "LLM_API_KEY", valueFrom = "${aws_secretsmanager_secret.app.arn}:LLM_API_KEY::" },
  ]

  db_secrets = [
    { name = "DATABASE_URL", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:DATABASE_URL::" },
    { name = "DATABASE_MIGRATOR_URL", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:DATABASE_MIGRATOR_URL::" },
  ]

  redis_url = "rediss://${aws_elasticache_replication_group.main.primary_endpoint_address}:6379/0"

  # ★ Twilio に登録する URL と 1 文字も違ってはいけない
  public_base_url = "https://${var.domain_name}"
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = "api"
    image     = "${var.ecr_registry}/kaden-api:${var.image_tag}"
    essential = true
    portMappings = [{ containerPort = 8080 }]
    environment = [
      { name = "APP_ENV", value = "production" },
      { name = "PORT", value = "8080" },
      { name = "REDIS_URL", value = local.redis_url },
      { name = "VOICE_BASE_URL", value = "${local.public_base_url}" },
      { name = "CORS_ORIGIN", value = local.public_base_url },
    ]
    secrets = concat(local.app_secrets, [
      { name = "SPRING_DATASOURCE_URL", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:SPRING_DATASOURCE_URL::" },
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:APP_USER::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:APP_PASSWORD::" },
      { name = "SPRING_FLYWAY_USER", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:MIGRATOR_USER::" },
      { name = "SPRING_FLYWAY_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db_app.arn}:MIGRATOR_PASSWORD::" },
    ])
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.main.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "api"
      }
    }
  }])
}

# voice の 3 プロセス。同じイメージで command だけを変える
resource "aws_ecs_task_definition" "voice" {
  for_each = {
    voice-web   = { command = ["web"], cpu = "512", memory = "1024" }
    voice-media = { command = ["media"], cpu = "1024", memory = "2048" }
    voice-jobs  = { command = ["jobs"], cpu = "512", memory = "1024" }
  }

  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = each.key
    image     = "${var.ecr_registry}/kaden-voice:${var.image_tag}"
    essential = true
    command   = each.value.command
    portMappings = each.key == "voice-jobs" ? [] : [{ containerPort = 8001 }]
    environment = [
      { name = "APP_ENV", value = "production" },
      { name = "PORT", value = "8001" },
      # ★ MEDIA_PORT を PORT と揃える。ずらすと ALB のヘルスチェックが通らない
      { name = "MEDIA_PORT", value = "8001" },
      { name = "REDIS_URL", value = local.redis_url },
      { name = "PUBLIC_BASE_URL", value = local.public_base_url },
      { name = "PUBLIC_WSS_URL", value = "wss://${var.domain_name}" },
      { name = "S3_BUCKET", value = aws_s3_bucket.recordings.id },
      { name = "S3_REGION", value = var.region },
      { name = "CORS_ORIGIN", value = local.public_base_url },
    ]
    secrets = concat(local.app_secrets, local.db_secrets)
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.main.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = each.key
      }
    }
  }])
}

resource "aws_ecs_task_definition" "web" {
  family                   = "${local.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name         = "web"
    image        = "${var.ecr_registry}/kaden-web:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 3000 }]
    environment  = [{ name = "NODE_ENV", value = "production" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.main.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "web"
      }
    }
  }])
}

# ---------------------------------------------------------------- サービス

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = [for s in aws_subnet.private : s.id]
    security_groups = [aws_security_group.app.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this["api"].arn
    container_name   = "api"
    container_port   = 8080
  }

  # ★ Flyway の起動に時間がかかる。短いとヘルスチェックが通る前に殺される
  health_check_grace_period_seconds = 120

  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "voice_web" {
  name            = "${local.name}-voice-web"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.voice["voice-web"].arn
  desired_count   = var.voice_web_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = [for s in aws_subnet.private : s.id]
    security_groups = [aws_security_group.app.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this["voice-web"].arn
    container_name   = "voice-web"
    container_port   = 8001
  }

  health_check_grace_period_seconds = 60
  depends_on                        = [aws_lb_listener.https]
}

resource "aws_ecs_service" "voice_media" {
  name            = "${local.name}-voice-media"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.voice["voice-media"].arn
  desired_count   = var.voice_media_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = [for s in aws_subnet.private : s.id]
    security_groups = [aws_security_group.app.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this["voice-media"].arn
    container_name   = "voice-media"
    container_port   = 8001
  }

  health_check_grace_period_seconds = 60
  depends_on                        = [aws_lb_listener.https]
}

# ★ ALB に繋がない。HTTP を持たないので、ターゲットグループを付けると
#   永久にヘルスチェック待ちになる
resource "aws_ecs_service" "voice_jobs" {
  name            = "${local.name}-voice-jobs"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.voice["voice-jobs"].arn
  # ★ 1 台だけ。複数だと同じ録音を 2 回取りに行く
  desired_count = 1
  launch_type   = "FARGATE"

  network_configuration {
    subnets         = [for s in aws_subnet.private : s.id]
    security_groups = [aws_security_group.app.id]
  }
}

resource "aws_ecs_service" "web" {
  name            = "${local.name}-web"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.web.arn
  desired_count   = var.web_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = [for s in aws_subnet.private : s.id]
    security_groups = [aws_security_group.app.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web"
    container_port   = 3000
  }

  depends_on = [aws_lb_listener.https]
}
