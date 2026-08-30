##############################################################################
# ネットワーク。
#
# ★ アプリと DB はプライベートサブネットに置き、インターネットからは
#   ALB 経由でしか触れない形にする。録音と顧客情報を持つので、
#   「とりあえずパブリックに置く」を最初からやらない。
#
# ★ NAT Gateway は 2 AZ ぶん立てる。1 つにすると、その AZ が落ちたときに
#   もう片方のサブネットから外部（Twilio API / Anthropic API）へ出られなくなる。
#   費用は上がるが、発信できない時間が生まれるほうが高くつく。
##############################################################################

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = local.name }
}

# ---------------------------------------------------------------- サブネット

resource "aws_subnet" "public" {
  for_each = { for idx, az in local.azs : az => idx }

  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, each.value)
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${each.key}" }
}

resource "aws_subnet" "private" {
  for_each = { for idx, az in local.azs : az => idx }

  vpc_id            = aws_vpc.main.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, each.value + 10)

  tags = { Name = "${local.name}-private-${each.key}" }
}

# ★ DB は専用のサブネットに隔離する。アプリのサブネットと分けておくと、
#   セキュリティグループを間違えたときの影響範囲が小さくなる
resource "aws_subnet" "data" {
  for_each = { for idx, az in local.azs : az => idx }

  vpc_id            = aws_vpc.main.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, each.value + 20)

  tags = { Name = "${local.name}-data-${each.key}" }
}

# ---------------------------------------------------------------- NAT

resource "aws_eip" "nat" {
  for_each = aws_subnet.public
  domain   = "vpc"
  tags     = { Name = "${local.name}-nat-${each.key}" }
}

resource "aws_nat_gateway" "main" {
  for_each = aws_subnet.public

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = each.value.id

  tags       = { Name = "${local.name}-${each.key}" }
  depends_on = [aws_internet_gateway.main]
}

# ---------------------------------------------------------------- ルート

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  for_each = aws_subnet.private

  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[each.key].id
  }

  tags = { Name = "${local.name}-private-${each.key}" }
}

resource "aws_route_table_association" "private" {
  for_each       = aws_subnet.private
  subnet_id      = each.value.id
  route_table_id = aws_route_table.private[each.key].id
}

# ★ data サブネットは外へ出さない。DB が外部と通信する理由が無い
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-data" }
}

resource "aws_route_table_association" "data" {
  for_each       = aws_subnet.data
  subnet_id      = each.value.id
  route_table_id = aws_route_table.data.id
}

# ---------------------------------------------------------------- SG

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "ALB。インターネットから 443 のみ"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # ★ 80 は 443 へのリダイレクトのためだけに開ける
  ingress {
    description = "HTTP（443 へリダイレクト）"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-alb" }
}

resource "aws_security_group" "app" {
  name        = "${local.name}-app"
  description = "ECS タスク。ALB からのみ受ける"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "ALB から"
    from_port       = 0
    to_port         = 65535
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # ★ 外向きは開ける。Twilio API・Anthropic API・S3 への通信が要る
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.name}-app" }
}

resource "aws_security_group" "data" {
  name        = "${local.name}-data"
  description = "RDS / ElastiCache。アプリからのみ"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  ingress {
    description     = "Redis"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  # ★ 外向きを開けない。DB から外部へ出る理由が無い
  tags = { Name = "${local.name}-data" }
}
