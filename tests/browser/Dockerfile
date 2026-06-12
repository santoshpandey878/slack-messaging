FROM mcr.microsoft.com/playwright:v1.52.0-noble

WORKDIR /tests
COPY package.json ./
RUN npm install --quiet
COPY . .

ENTRYPOINT ["npx", "playwright", "test"]
