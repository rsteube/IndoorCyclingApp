# What Play actually holds for a track: release status and the fraction of
# riders it is going out to. `google_play_track_version_codes` answers only the
# first half of that, and a staged rollout is all in the second half.
require "google/apis/androidpublisher_v3"
require "googleauth"

PACKAGE = "app.indoorbike"
KEY = File.expand_path("../../../play-service-account.json", __dir__)
TRACKS = ARGV.empty? ? %w[production internal] : ARGV

service = Google::Apis::AndroidpublisherV3::AndroidPublisherService.new
service.authorization = Google::Auth::ServiceAccountCredentials.make_creds(
  json_key_io: File.open(KEY),
  scope: "https://www.googleapis.com/auth/androidpublisher"
)

edit = service.insert_edit(PACKAGE)
TRACKS.each do |name|
  track = service.get_edit_track(PACKAGE, edit.id, name)
  track.releases.to_a.each do |r|
    puts "#{name}: codes=#{r.version_codes.inspect} status=#{r.status} " \
         "fraction=#{r.user_fraction.inspect} name=#{r.name.inspect}"
  end
end
service.delete_edit(PACKAGE, edit.id)
