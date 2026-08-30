variable "region" {
  description = "デプロイ先リージョン"
  type        = string
  # ★ 通話録音と顧客情報を扱うので、既定は国内。
  #   データの保存地域は契約と法令の確認対象になる
  default = "ap-northeast-1"
}

variable "environment" {
  description = "環境名（prod / stg など）"
  type        = string
  default     = "prod"
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "domain_name" {
  description = "公開ドメイン。★ Twilio に登録する URL と一致させること"
  type        = string
}

variable "certificate_arn" {
  description = "ACM の証明書 ARN（domain_name 用）"
  type        = string
}

# ---------------------------------------------------------------- DB

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_allocated_storage" {
  type    = number
  default = 50
}

variable "db_multi_az" {
  description = "★ 本番は true。片方の AZ が落ちても架電を止めないため"
  type        = bool
  default     = true
}

# ---------------------------------------------------------------- 台数

variable "api_desired_count" {
  type    = number
  default = 2
}

variable "voice_web_desired_count" {
  type    = number
  default = 2
}

variable "voice_media_desired_count" {
  description = <<-EOT
    音声ワーカーの台数。
    ★ 同時通話数で決まる。1 タスクあたりの目安は 25〜50 通話。
      webhook 側（voice_web）とは別の軸なので、まとめてスケールさせない。
  EOT
  type        = number
  default     = 2
}

variable "web_desired_count" {
  type    = number
  default = 2
}

# ---------------------------------------------------------------- 録音

variable "recording_retention_days" {
  description = <<-EOT
    録音の保存日数（S3 のライフサイクル）。
    ★ テナントごとの保存期間はアプリ（tenants.recording_retention_days）が持ち、
      定期ジョブが消す。ここはその取りこぼしに対する最後の網なので、
      アプリ側の最大値より長くしておくこと。短くすると、
      まだ保存期間内の録音が消える。
  EOT
  type    = number
  default = 400
}

variable "image_tag" {
  description = "デプロイするイメージのタグ"
  type        = string
  default     = "latest"
}

variable "ecr_registry" {
  description = "ECR のレジストリ URI（例 123456789012.dkr.ecr.ap-northeast-1.amazonaws.com）"
  type        = string
}
