-- Final, repeatable setup for DORAMAX automatic Lucas catalog synchronization.
-- pg_cron runs in Postgres; pg_net fetches the public YouTube RSS feed.
-- The immutable channel ID and feed-title validation prevent handle reassignment
-- from importing videos from a different channel.
CREATE SCHEMA IF NOT EXISTS private;
CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;

CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
CREATE TABLE IF NOT EXISTS private.youtube_sync_state (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  request_id bigint,
  last_attempt_at timestamptz,
  last_success_at timestamptz,
  last_error text
);
REVOKE ALL ON private.youtube_sync_state FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION private.sync_lucas_catalog()
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, public, private, net
AS $$
DECLARE
  v_channel_id constant text := 'UCmbVDAVpU2jKOQ_HTLtvxwA';
  v_handle constant text := 'lucasdramascurtos';
  v_request_id bigint;
  v_status integer;
  v_body text;
  v_doc xml;
  v_entry xml;
  v_video_id text;
  v_title text;
  v_published text;
  v_entry_channel text;
  v_feed_title text;
  v_position integer := 0;
  v_saved integer := 0;
  v_active_count integer := 0;
BEGIN
  SELECT request_id INTO v_request_id FROM private.youtube_sync_state WHERE singleton = true;
  IF NOT FOUND OR v_request_id IS NULL THEN
    v_request_id := net.http_get(
      'https://www.youtube.com/feeds/videos.xml?channel_id=' || v_channel_id,
      headers := jsonb_build_object('User-Agent','Mozilla/5.0 (compatible; DORAMAXCatalog/1.0)')
    );
    INSERT INTO private.youtube_sync_state(singleton,request_id,last_attempt_at)
    VALUES (true,v_request_id,now())
    ON CONFLICT (singleton) DO UPDATE SET request_id=EXCLUDED.request_id,last_attempt_at=EXCLUDED.last_attempt_at,last_error=NULL;
    RETURN;
  END IF;
  SELECT status_code,content INTO v_status,v_body FROM net._http_response WHERE id=v_request_id;
  IF NOT FOUND THEN RETURN; END IF;
  BEGIN
    IF v_status <> 200 OR v_body IS NULL THEN RAISE EXCEPTION 'YouTube RSS request failed (HTTP %)',v_status; END IF;
    v_doc := v_body::xml;
    v_feed_title := (xpath('string(/*[local-name()="feed"]/*[local-name()="title"])',v_doc))[1]::text;
    IF lower(coalesce(v_feed_title,'')) <> lower('Lucas Dramas Curtos') THEN
      RAISE EXCEPTION 'Unexpected YouTube channel in RSS feed: %',coalesce(v_feed_title,'(empty)');
    END IF;
    FOR v_entry IN SELECT unnest(xpath('//*[local-name()="entry"]',v_doc))
    LOOP
      v_video_id := (xpath('string(//*[local-name()="videoId"])',v_entry))[1]::text;
      v_title := (xpath('string(//*[local-name()="title"])',v_entry))[1]::text;
      v_published := (xpath('string(//*[local-name()="published"])',v_entry))[1]::text;
      v_entry_channel := (xpath('string(//*[local-name()="channelId"])',v_entry))[1]::text;
      IF coalesce(v_video_id,'') <> '' AND coalesce(v_title,'') <> '' AND v_entry_channel=v_channel_id THEN
        INSERT INTO public.videos(id,title,published,source,is_active,position,manual_override,updated_at)
        VALUES(v_video_id,v_title,coalesce(v_published,''),v_handle,true,v_position,false,now())
        ON CONFLICT(id) DO UPDATE SET title=EXCLUDED.title,published=EXCLUDED.published,source=EXCLUDED.source,
          is_active=true,position=EXCLUDED.position,updated_at=now()
        WHERE public.videos.manual_override=false;
        v_position:=v_position+1;
        v_saved:=v_saved+1;
      END IF;
    END LOOP;
    IF v_saved=0 THEN RAISE EXCEPTION 'RSS feed had no valid videos for the pinned Lucas channel ID.'; END IF;
    SELECT count(*) INTO v_active_count FROM public.videos WHERE is_active=true;
    UPDATE public.app_settings SET value=value||jsonb_build_object(
      'youtube_channel_id',v_channel_id,'youtube_channels',jsonb_build_array(v_handle),
      'catalog_synced_at',now(),'catalog_updated_at',now(),'remote_catalog_source','supabase',
      'app_remote_content_enabled',true,'video_count',v_active_count,'last_verified_at',now()
    ),updated_at=now() WHERE key='app_config';
    UPDATE private.youtube_sync_state SET last_success_at=now(),last_error=NULL WHERE singleton=true;
  EXCEPTION WHEN OTHERS THEN
    UPDATE private.youtube_sync_state SET last_error=SQLERRM WHERE singleton=true;
  END;
  v_request_id := net.http_get(
    'https://www.youtube.com/feeds/videos.xml?channel_id='||v_channel_id,
    headers:=jsonb_build_object('User-Agent','Mozilla/5.0 (compatible; DORAMAXCatalog/1.0)')
  );
  UPDATE private.youtube_sync_state SET request_id=v_request_id,last_attempt_at=now() WHERE singleton=true;
END;
$$;
REVOKE ALL ON FUNCTION private.sync_lucas_catalog() FROM PUBLIC,anon,authenticated;

DO $$
DECLARE j record;
BEGIN
  FOR j IN SELECT jobid FROM cron.job WHERE jobname='doramax-lucas-catalog-sync'
  LOOP PERFORM cron.unschedule(j.jobid); END LOOP;
  PERFORM cron.schedule('doramax-lucas-catalog-sync','*/30 * * * *','SELECT private.sync_lucas_catalog();');
END;
$$;
