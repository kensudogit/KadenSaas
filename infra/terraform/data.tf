##############################################################################
# データストア（RDS / ElastiCache / S3 / Secrets Manager）。
##############################################################################

# ---------------------------------------------------------------- RDS

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = [for s in aws_subnet.data : s.id]
}

resource "aws_db_parameter_group" "main" {
  name   = local.name
  family = "postgres16"

  # ★ 遅いクエリを記録する。架電中に API が詰まる原因の切り分けに要る
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 4
  storage_type          = "gp3"
  # ★ 顧客情報と通話記録が入る。保管時の暗号化は既定で有効にする
  storage_encrypted = true

  db_name  = "kaden"
  username = "kaden_admin"
  # ★ パスワードを Terraform に書かない。RDS が生成し Secrets Manager が持つ
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.data.id]
  parameter_group_name   = aws_db_parameter_group.main.name

  multi_az = var.db_multi_az

  backup_retention_period = 14
  backup_window           = "18:00-19:00" # JST 03:00-04:00
  maintenance_window       = "Mon:19:00-Mon:20:00"

  # ★ 削除保護。通話記録は復元できない
  deletion_protection      = true
  skip_final_snapshot      = false
  final_snapshot_identifier = "${local.name}-final"

  performance_insights_enabled = true
  enabled_cloudwatch_logs_exports = ["postgresql"]

  tags = { Name = local.name }
}

# ---------------------------------------------------------------- Redis

resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = [for s in aws_subnet.data : s.id]
}

# ★ 通話中の文字起こしを画面へ流す pub/sub に使う。
#   落ちても通話は続く（リアルタイム表示が出なくなるだけ）設計なので、
#   ここは可用性より単純さを取る。
resource "aws_elasticache_replication_group" "main" {
  replication_group_id = local.name
  description          = "kaden pub/sub"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = "cache.t4g.micro"

  num_cache_clusters         = 2
  automatic_failover_enabled = true

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.data.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
}

# ---------------------------------------------------------------- S3（録音）

resource "aws_s3_bucket" "recordings" {
  bucket = "${local.name}-recordings"

  tags = { Name = "${local.name}-recordings" }
}

# ★ 録音は個人情報。公開の可能性を全部塞ぐ。
#   署名 URL でしか読ませない
resource "aws_s3_bucket_public_access_block" "recordings" {
  bucket = aws_s3_bucket.recordings.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "recordings" {
  bucket = aws_s3_bucket.recordings.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "recordings" {
  bucket = aws_s3_bucket.recordings.id
  versioning_configuration {
    # ★ 有効にしない。録音を消したのに版が残ると、
    #   保存期間の約束を守れていないことになる
    status = "Disabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "recordings" {
  bucket = aws_s3_bucket.recordings.id

  rule {
    id     = "retention"
    status = "Enabled"

    filter {
      prefix = "tenants/"
    }

    # ★ 最後の網。テナントごとの保存期間はアプリの定期ジョブが消す。
    #   こちらはジョブが止まっていた場合に備えたもので、
    #   アプリ側の最大保存期間より必ず長くする
    expiration {
      days = var.recording_retention_days
    }
  }
}

# ---------------------------------------------------------------- 秘密情報

# ★ Twilio の Auth Token は「他人名義で電話をかけられる鍵」であり、
#   同時に Webhook の署名検証鍵。環境変数に平文で置かない。
#   値は Terraform では設定せず、コンソールか CLI で入れる
#   （tfstate に平文で残さないため）。
resource "aws_secretsmanager_secret" "app" {
  name        = "${local.name}/app"
  description = "JWT_SECRET / Twilio / ASR / LLM の資格情報"

  # ★ 誤削除しても 30 日は戻せる
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret" "db_app" {
  name        = "${local.name}/db-app"
  description = "kaden_app / kaden_migrator の接続情報"

  recovery_window_in_days = 30
}
