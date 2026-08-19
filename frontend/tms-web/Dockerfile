# TMS by EBIM - frontend container image.
#
# Vite compiles to static files, so the runtime stage is nginx and a directory - there is no
# Node process in the shipped image.
#
# Note that VITE_* values are baked into the bundle at BUILD time, not read at run time. They
# are therefore build arguments, and changing the API URL means rebuilding the image. Only
# values that are safe to publish belong here: the Supabase anon key is designed to be public,
# a service-role key is not and must never be passed to this build.

# --------------------------------------------------------------------------------------------
# Stage 1 - build
# --------------------------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# Dependencies in their own layer, keyed on the lockfile: application edits do not re-install.
# `npm ci` rather than `npm install` so the build uses exactly the locked tree.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG VITE_API_BASE_URL
ARG VITE_SUPABASE_URL
ARG VITE_SUPABASE_ANON_KEY
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_SUPABASE_URL=$VITE_SUPABASE_URL \
    VITE_SUPABASE_ANON_KEY=$VITE_SUPABASE_ANON_KEY

# `npm run build` is `tsc -b && vite build`: a type error fails the image build rather than
# shipping a bundle that compiled by luck.
RUN npm run build

# --------------------------------------------------------------------------------------------
# Stage 2 - runtime
# --------------------------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# The nginx image substitutes environment variables into anything under /etc/nginx/templates
# on startup, which is how the server picks up the port the platform assigns.
ENV PORT=8080
EXPOSE 8080
